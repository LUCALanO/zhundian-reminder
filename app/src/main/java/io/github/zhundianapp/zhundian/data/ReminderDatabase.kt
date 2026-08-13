package io.github.zhundianapp.zhundian.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Reminder::class, TriggerRecord::class, CalendarEvent::class],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ReminderDatabase : RoomDatabase() {

    abstract fun reminderDao(): ReminderDao

    abstract fun triggerRecordDao(): TriggerRecordDao

    abstract fun calendarEventDao(): CalendarEventDao

    companion object {
        /** v1→v2：新增「顶部弹窗」开关列，默认关闭，旧数据不丢。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reminders ADD COLUMN overlayEnabled INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v2→v3：新增自定义提示语列（可空）+ 触发历史表，旧数据保留。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN message TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trigger_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`reminderId` INTEGER NOT NULL, " +
                        "`triggerAt` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`resolvedAt` INTEGER)"
                )
            }
        }

        /** v3→v4：新增自定义铃声 URI 列（可空，null 用系统默认闹钟铃声），旧数据保留。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN soundUri TEXT")
            }
        }

        /** v4→v5：新增响铃音量与响铃时长列，存量提醒默认 0.7 音量、5 秒响铃。 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN soundVolume REAL NOT NULL DEFAULT 0.7")
                db.execSQL("ALTER TABLE reminders ADD COLUMN soundDurationSeconds INTEGER NOT NULL DEFAULT 5")
            }
        }

        /**
         * v5→v6：新增系统日历日程表 calendar_events。
         * 索引名须遵循 Room 的「字段下划线连接」命名规则，否则迁移后 schema 校验失败。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calendar_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`description` TEXT, " +
                        "`location` TEXT, " +
                        "`startAt` INTEGER NOT NULL, " +
                        "`endAt` INTEGER NOT NULL, " +
                        "`allDay` INTEGER NOT NULL, " +
                        "`sourceEventId` INTEGER NOT NULL, " +
                        "`sourceCalendarId` INTEGER NOT NULL, " +
                        "`reminded` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_calendar_events_sourceEventId_startAt` " +
                        "ON `calendar_events` (`sourceEventId`, `startAt`)"
                )
            }
        }

        /**
         * v6→v7：calendar_events 新增 permanentlyDeleted 列（永久删除标记）。
         * 默认 0，旧数据（普通墓碑或正常日程）不受影响。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE calendar_events ADD COLUMN permanentlyDeleted INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v7→v8：calendar_events 新增 App 自建日程三列——isLocal（本地标记）、
         * syncToSystem（是否推送到系统日历）、systemEventId（推送成功后系统 Events._ID）。
         * 默认 0 / 0 / NULL，存量系统导入数据不受影响。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE calendar_events ADD COLUMN isLocal INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE calendar_events ADD COLUMN syncToSystem INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN systemEventId INTEGER")
            }
        }

        /**
         * v8→v9：移除「同步到系统日历」功能，calendar_events 删除 syncToSystem、systemEventId 两列。
         *
         * 用重建表而非 `ALTER TABLE DROP COLUMN`（旧版 SQLite 不支持 DROP COLUMN）：
         * 建新表 → 拷数据（含 id/createdAt/isLocal）→ 删旧表 → 改名 → 重建唯一索引，
         * 保证 Room schema 校验（表结构 + 索引）一致，旧数据不丢。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calendar_events_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`description` TEXT, " +
                        "`location` TEXT, " +
                        "`startAt` INTEGER NOT NULL, " +
                        "`endAt` INTEGER NOT NULL, " +
                        "`allDay` INTEGER NOT NULL, " +
                        "`sourceEventId` INTEGER NOT NULL, " +
                        "`sourceCalendarId` INTEGER NOT NULL, " +
                        "`isLocal` INTEGER NOT NULL, " +
                        "`reminded` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, " +
                        "`permanentlyDeleted` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO calendar_events_new (id, title, description, location, startAt, endAt, " +
                        "allDay, sourceEventId, sourceCalendarId, isLocal, reminded, deleted, " +
                        "permanentlyDeleted, createdAt) " +
                        "SELECT id, title, description, location, startAt, endAt, allDay, " +
                        "sourceEventId, sourceCalendarId, isLocal, reminded, deleted, " +
                        "permanentlyDeleted, createdAt FROM calendar_events"
                )
                db.execSQL("DROP TABLE calendar_events")
                db.execSQL("ALTER TABLE calendar_events_new RENAME TO calendar_events")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_events_sourceEventId_startAt` " +
                        "ON `calendar_events` (`sourceEventId`, `startAt`)"
                )
            }
        }

        /**
         * v9→v10：reminders 新增 scheduleAnchorAt 列（可空，「天」间隔的锚定触发时刻）。
         * 默认 NULL，存量提醒保持旧行为（不锚定）不受影响。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN scheduleAnchorAt INTEGER")
            }
        }

        @Volatile
        private var instance: ReminderDatabase? = null

        fun getInstance(context: Context): ReminderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReminderDatabase::class.java,
                    "interval_reminder.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
                )
                    .build().also { instance = it }
            }
    }
}
