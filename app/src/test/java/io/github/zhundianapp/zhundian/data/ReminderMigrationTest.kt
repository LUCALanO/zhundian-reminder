package io.github.zhundianapp.zhundian.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1→v2 / v2→v3 迁移测试。
 *
 * 项目 exportSchema=false，MigrationTestHelper 无法精确重建旧版表结构，
 * 故手写与 Room 生成一致的旧版 schema 直接验证迁移 SQL。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReminderMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-test.db"

    /** 与 Room 生成的 v1 reminders 表一致（不含 overlayEnabled 列）。 */
    private val createV1 = """
        CREATE TABLE IF NOT EXISTS `reminders` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `intervalValue` INTEGER NOT NULL,
            `intervalUnit` TEXT NOT NULL,
            `soundEnabled` INTEGER NOT NULL,
            `vibrationEnabled` INTEGER NOT NULL,
            `isEnabled` INTEGER NOT NULL,
            `nextTriggerAt` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL
        )
    """.trimIndent()

    /** 与 Room 生成的 v2 reminders 表一致（含 overlayEnabled 列，不含 message 列）。 */
    private val createV2 = """
        CREATE TABLE IF NOT EXISTS `reminders` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `intervalValue` INTEGER NOT NULL,
            `intervalUnit` TEXT NOT NULL,
            `soundEnabled` INTEGER NOT NULL,
            `vibrationEnabled` INTEGER NOT NULL,
            `overlayEnabled` INTEGER NOT NULL,
            `isEnabled` INTEGER NOT NULL,
            `nextTriggerAt` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL
        )
    """.trimIndent()

    /** 与 Room 生成的 v3 reminders 表一致（含 message 列，不含 soundUri 列）。 */
    private val createV3 = """
        CREATE TABLE IF NOT EXISTS `reminders` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `intervalValue` INTEGER NOT NULL,
            `intervalUnit` TEXT NOT NULL,
            `soundEnabled` INTEGER NOT NULL,
            `vibrationEnabled` INTEGER NOT NULL,
            `overlayEnabled` INTEGER NOT NULL,
            `isEnabled` INTEGER NOT NULL,
            `nextTriggerAt` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `message` TEXT
        )
    """.trimIndent()

    /** 与 Room 生成的 v4 reminders 表一致（含 soundUri 列，不含音量/时长列）。 */
    private val createV4 = """
        CREATE TABLE IF NOT EXISTS `reminders` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `intervalValue` INTEGER NOT NULL,
            `intervalUnit` TEXT NOT NULL,
            `soundEnabled` INTEGER NOT NULL,
            `vibrationEnabled` INTEGER NOT NULL,
            `overlayEnabled` INTEGER NOT NULL,
            `isEnabled` INTEGER NOT NULL,
            `nextTriggerAt` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `message` TEXT,
            `soundUri` TEXT
        )
    """.trimIndent()

    /** 与 v3 迁移创建且后续版本未改动的 trigger_records 表一致。 */
    private val createTriggerRecords = """
        CREATE TABLE IF NOT EXISTS `trigger_records` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `reminderId` INTEGER NOT NULL,
            `triggerAt` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `resolvedAt` INTEGER
        )
    """.trimIndent()

    /** 与 Room 生成的 v9 reminders 表一致（v5 起未再增列，含音量/时长，不含 scheduleAnchorAt）。 */
    private val createV9 = """
        CREATE TABLE IF NOT EXISTS `reminders` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `intervalValue` INTEGER NOT NULL,
            `intervalUnit` TEXT NOT NULL,
            `soundEnabled` INTEGER NOT NULL,
            `vibrationEnabled` INTEGER NOT NULL,
            `overlayEnabled` INTEGER NOT NULL,
            `isEnabled` INTEGER NOT NULL,
            `nextTriggerAt` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `message` TEXT,
            `soundUri` TEXT,
            `soundVolume` REAL NOT NULL,
            `soundDurationSeconds` INTEGER NOT NULL
        )
    """.trimIndent()

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2_addsOverlayColumnDefaultFalse_preservesData() {
        val db = openV1Database()
        db.execSQL(createV1)
        db.execSQL(
            "INSERT INTO reminders (name, intervalValue, intervalUnit, soundEnabled, vibrationEnabled, " +
                "isEnabled, nextTriggerAt, createdAt) " +
                "VALUES ('吃药', 2, 'DAYS', 1, 1, 1, 1000, 1000)"
        )

        ReminderDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT name, overlayEnabled FROM reminders").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧数据应保留", "吃药", cursor.getString(0))
            assertEquals("新增列默认应为关闭(0)", 0, cursor.getInt(1))
        }
        db.close()
    }

    @Test
    fun migrate2To3_addsMessageColumnAndTriggerTable_preservesData() {
        val db = openV1Database()
        db.execSQL(createV2)
        db.execSQL(
            "INSERT INTO reminders (name, intervalValue, intervalUnit, soundEnabled, vibrationEnabled, " +
                "overlayEnabled, isEnabled, nextTriggerAt, createdAt) " +
                "VALUES ('吃药', 2, 'DAYS', 1, 1, 1, 1, 1000, 1000)"
        )

        ReminderDatabase.MIGRATION_2_3.migrate(db)

        db.query("SELECT name, message FROM reminders").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧数据应保留", "吃药", cursor.getString(0))
            assertNull("新增 message 列应为 NULL", cursor.getString(1))
        }
        // trigger_records 表迁移后可用
        db.execSQL(
            "INSERT INTO trigger_records (reminderId, triggerAt, status, resolvedAt) " +
                "VALUES (1, 2000, 'TRIGGERED', NULL)"
        )
        db.query("SELECT reminderId, status FROM trigger_records").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("TRIGGERED", cursor.getString(1))
        }
        db.close()
    }

    @Test
    fun migrate3To4_addsSoundUriColumnNull_preservesData() {
        val db = openV1Database()
        db.execSQL(createV3)
        db.execSQL(
            "INSERT INTO reminders (name, intervalValue, intervalUnit, soundEnabled, vibrationEnabled, " +
                "overlayEnabled, isEnabled, nextTriggerAt, createdAt) " +
                "VALUES ('吃药', 2, 'DAYS', 1, 1, 0, 1, 1000, 1000)"
        )

        ReminderDatabase.MIGRATION_3_4.migrate(db)

        db.query("SELECT name, soundUri FROM reminders").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧数据应保留", "吃药", cursor.getString(0))
            assertNull("新增 soundUri 列应为 NULL（null 表示用系统默认闹钟铃声）", cursor.getString(1))
        }
        db.close()
    }

    @Test
    fun migrate4To5_addsVolumeAndDurationColumns_preservesData() {
        val db = openV1Database()
        db.execSQL(createV4)
        db.execSQL(
            "INSERT INTO reminders (name, intervalValue, intervalUnit, soundEnabled, vibrationEnabled, " +
                "overlayEnabled, isEnabled, nextTriggerAt, createdAt, message, soundUri) " +
                "VALUES ('吃药', 2, 'DAYS', 1, 1, 0, 1, 1000, 1000, '该起来了', 'content://media/1001')"
        )

        ReminderDatabase.MIGRATION_4_5.migrate(db)

        db.query("SELECT name, soundVolume, soundDurationSeconds, soundUri FROM reminders").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧数据应保留", "吃药", cursor.getString(0))
            assertEquals("新增音量列默认应为 0.7", 0.7f, cursor.getFloat(1), 0.0001f)
            assertEquals("新增时长列默认应为 5", 5, cursor.getInt(2))
            assertEquals("soundUri 应保留", "content://media/1001", cursor.getString(3))
        }
        db.close()
    }

    @Test
    fun migrate9To10_addsScheduleAnchorColumn_preservesData() {
        val db = openDatabaseAtVersion(9)
        db.execSQL(createV9)
        db.execSQL(
            "INSERT INTO reminders (name, intervalValue, intervalUnit, soundEnabled, vibrationEnabled, " +
                "overlayEnabled, isEnabled, nextTriggerAt, createdAt, message, soundUri, " +
                "soundVolume, soundDurationSeconds) " +
                "VALUES ('吃药', 2, 'DAYS', 1, 1, 0, 1, 1000, 1000, '该起来了', " +
                "'content://media/1001', 0.7, 5)"
        )

        ReminderDatabase.MIGRATION_9_10.migrate(db)

        db.query("SELECT name, scheduleAnchorAt FROM reminders").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧数据应保留", "吃药", cursor.getString(0))
            assertTrue("新增 scheduleAnchorAt 列默认应为 NULL", cursor.isNull(1))
        }
        db.close()
    }

    @Test
    fun migrate4To5_realRoomOpen_validatesSchema() {
        // 手工建 v4 库（无 room_master_table），再让 Room 走全部迁移（到 v10）打开：
        // 会触发最终 schema 校验，能提前暴露 ADD COLUMN 默认值与实体期望不一致的问题。
        val db = openDatabaseAtVersion(4)
        db.execSQL(createV4)
        db.execSQL(createTriggerRecords)
        db.execSQL(
            "INSERT INTO reminders (name, intervalValue, intervalUnit, soundEnabled, vibrationEnabled, " +
                "overlayEnabled, isEnabled, nextTriggerAt, createdAt, soundUri) " +
                "VALUES ('吃药', 2, 'DAYS', 1, 1, 0, 1, 1000, 1000, 'content://media/1001')"
        )
        db.close()

        val room = Room.databaseBuilder(context, ReminderDatabase::class.java, dbName)
            .addMigrations(
                ReminderDatabase.MIGRATION_4_5,
                ReminderDatabase.MIGRATION_5_6,
                ReminderDatabase.MIGRATION_6_7,
                ReminderDatabase.MIGRATION_7_8,
                ReminderDatabase.MIGRATION_8_9,
                ReminderDatabase.MIGRATION_9_10
            )
            .build()
        try {
            val loaded = runBlocking { room.reminderDao().getById(1L) }
            assertNotNull("迁移后旧数据可读", loaded)
            assertEquals("吃药", loaded?.name)
            assertEquals("新列默认值应回读为 0.7", 0.7f, loaded?.soundVolume ?: 0f, 0.0001f)
            assertEquals("新列默认值应回读为 5", 5, loaded?.soundDurationSeconds)
            assertNull("scheduleAnchorAt 应回读为 null（未锚定）", loaded?.scheduleAnchorAt)
        } finally {
            room.close()
        }
    }

    private fun openV1Database(): SupportSQLiteDatabase = openDatabaseAtVersion(1)

    /** 打开一个 user_version = [version] 的空库，用于模拟历史版本的手工建表。 */
    private fun openDatabaseAtVersion(version: Int): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }
}
