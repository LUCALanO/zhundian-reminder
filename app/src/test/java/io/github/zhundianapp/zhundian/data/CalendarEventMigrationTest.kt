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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v5→v6 迁移测试：新增 calendar_events 表 + 唯一索引，旧 reminders 数据保留。
 * 与 [ReminderMigrationTest] 同一思路（exportSchema=false，手写旧版 schema 验证迁移 SQL）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CalendarEventMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-test-v6.db"

    /** 与 Room 生成的 v5 reminders 表一致（最终版本，含音量/时长列）。 */
    private val createV5Reminders = """
        CREATE TABLE IF NOT EXISTS `reminders` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `intervalValue` INTEGER NOT NULL,
            `intervalUnit` TEXT NOT NULL,
            `soundEnabled` INTEGER NOT NULL,
            `soundUri` TEXT,
            `soundVolume` REAL NOT NULL DEFAULT 0.7,
            `soundDurationSeconds` INTEGER NOT NULL DEFAULT 5,
            `vibrationEnabled` INTEGER NOT NULL,
            `overlayEnabled` INTEGER NOT NULL,
            `isEnabled` INTEGER NOT NULL,
            `nextTriggerAt` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `message` TEXT
        )
    """.trimIndent()

    private val createTriggerRecords = """
        CREATE TABLE IF NOT EXISTS `trigger_records` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `reminderId` INTEGER NOT NULL,
            `triggerAt` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `resolvedAt` INTEGER
        )
    """.trimIndent()

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate5To6_createsTableIndex_preservesOldData() {
        val db = openDatabaseAtVersion(5)
        db.execSQL(createV5Reminders)
        db.execSQL(createTriggerRecords)
        db.execSQL(
            "INSERT INTO reminders (name, intervalValue, intervalUnit, soundEnabled, soundUri, " +
                "soundVolume, soundDurationSeconds, vibrationEnabled, overlayEnabled, isEnabled, " +
                "nextTriggerAt, createdAt, message) " +
                "VALUES ('吃药', 2, 'DAYS', 1, NULL, 0.7, 5, 1, 0, 1, 1000, 1000, '该起来了')"
        )

        ReminderDatabase.MIGRATION_5_6.migrate(db)

        // 旧 reminders 数据保留
        db.query("SELECT name, soundVolume, message FROM reminders").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧数据应保留", "吃药", cursor.getString(0))
            assertEquals(0.7f, cursor.getFloat(1), 0.0001f)
            assertEquals("该起来了", cursor.getString(2))
        }
        // calendar_events 表可用 + 唯一索引生效
        db.execSQL(
            "INSERT INTO calendar_events (title, description, location, startAt, endAt, allDay, " +
                "sourceEventId, sourceCalendarId, reminded, deleted, createdAt) " +
                "VALUES ('会议', NULL, '会议室', 1000, 2000, 0, 1, 1, 0, 0, 1000)"
        )
        db.query("SELECT title, sourceEventId FROM calendar_events").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("会议", cursor.getString(0))
            assertEquals(1L, cursor.getLong(1))
        }
        db.close()
    }

    @Test
    fun migrate5To6_realRoomOpen_validatesSchema() {
        // 手工建 v5 库（无 room_master_table），再让 Room 走 MIGRATION_5_6 打开到 v6，
        // 触发最终 schema 校验，验证表结构/索引与实体期望一致。
        val db = openDatabaseAtVersion(5)
        db.execSQL(createV5Reminders)
        db.execSQL(createTriggerRecords)
        db.execSQL(
            "INSERT INTO reminders (name, intervalValue, intervalUnit, soundEnabled, soundUri, " +
                "soundVolume, soundDurationSeconds, vibrationEnabled, overlayEnabled, isEnabled, " +
                "nextTriggerAt, createdAt, message) " +
                "VALUES ('吃药', 2, 'DAYS', 1, NULL, 0.7, 5, 1, 0, 1, 1000, 1000, '该起来了')"
        )
        db.close()

        val room = Room.databaseBuilder(context, ReminderDatabase::class.java, dbName)
            .addMigrations(
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

            // 新表可写可读
            runBlocking {
                room.calendarEventDao().insertIgnore(
                    CalendarEvent(
                        title = "晨会", startAt = 5000, endAt = 6000,
                        sourceEventId = 10, sourceCalendarId = 2
                    )
                )
            }
            val inserted = runBlocking { room.calendarEventDao().getById(1L) }
            assertNotNull("新表可读写", inserted)
            assertEquals("晨会", inserted?.title)

            // 唯一索引生效：重复 (sourceEventId, startAt) 应被 IGNORE 而不报错
            val duplicateId = runBlocking {
                room.calendarEventDao().insertIgnore(
                    CalendarEvent(
                        title = "重复", startAt = 5000, endAt = 6000,
                        sourceEventId = 10, sourceCalendarId = 2
                    )
                )
            }
            assertEquals("重复键应被忽略（返回 -1）", -1L, duplicateId)
            val stillOne = runBlocking {
                room.calendarEventDao().getUpcoming(0L).filter { it.sourceEventId == 10L }
            }
            assertEquals("同源同时间只应有一条", 1, stillOne.size)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate6To7_addsColumn_realRoomOpen_validatesSchema() {
        // 手工建 v6 库（calendar_events 无 permanentlyDeleted 列），Room 走 MIGRATION_6_7 打开到 v7，
        // 触发最终 schema 校验，验证新列存在、旧数据保留、默认 0、可写。
        val db = openDatabaseAtVersion(6)
        db.execSQL(createV5Reminders)
        db.execSQL(createTriggerRecords)
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
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_events_sourceEventId_startAt` " +
                "ON `calendar_events` (`sourceEventId`, `startAt`)"
        )
        db.execSQL(
            "INSERT INTO calendar_events (title, description, location, startAt, endAt, allDay, " +
                "sourceEventId, sourceCalendarId, reminded, deleted, createdAt) " +
                "VALUES ('会议', NULL, '会议室', 1000, 2000, 0, 1, 1, 0, 0, 1000)"
        )
        db.close()

        val room = Room.databaseBuilder(context, ReminderDatabase::class.java, dbName)
            .addMigrations(
                ReminderDatabase.MIGRATION_6_7,
                ReminderDatabase.MIGRATION_7_8,
                ReminderDatabase.MIGRATION_8_9,
                ReminderDatabase.MIGRATION_9_10
            )
            .build()
        try {
            val loaded = runBlocking { room.calendarEventDao().getById(1L) }
            assertNotNull("迁移后旧数据可读", loaded)
            assertEquals("会议", loaded?.title)
            assertEquals("permanentlyDeleted 默认 0", false, loaded?.permanentlyDeleted)

            // 新列可写（永久删除标记）
            runBlocking { room.calendarEventDao().markDeletedPermanently(1L) }
            val permanent = runBlocking { room.calendarEventDao().getById(1L) }
            assertTrue("permanentlyDeleted 可写", permanent!!.permanentlyDeleted)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate7To8_addsLocalColumns_realRoomOpen_validatesSchema() {
        // 手工建 v7 库（calendar_events 无 isLocal/syncToSystem/systemEventId 列），
        // Room 走 MIGRATION_7_8 + MIGRATION_8_9 + MIGRATION_9_10 打开到 v10：旧数据保留、isLocal 可写、
        // pruneOutside 不裁剪本地行，且 syncToSystem/systemEventId 列已被 v8→v9 移除。
        val db = openDatabaseAtVersion(7)
        db.execSQL(createV5Reminders)
        db.execSQL(createTriggerRecords)
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
                "`permanentlyDeleted` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_events_sourceEventId_startAt` " +
                "ON `calendar_events` (`sourceEventId`, `startAt`)"
        )
        db.execSQL(
            "INSERT INTO calendar_events (title, description, location, startAt, endAt, allDay, " +
                "sourceEventId, sourceCalendarId, reminded, deleted, permanentlyDeleted, createdAt) " +
                "VALUES ('会议', NULL, '会议室', 1000, 2000, 0, 1, 1, 0, 0, 0, 1000)"
        )
        db.close()

        val room = Room.databaseBuilder(context, ReminderDatabase::class.java, dbName)
            .addMigrations(
                ReminderDatabase.MIGRATION_7_8,
                ReminderDatabase.MIGRATION_8_9,
                ReminderDatabase.MIGRATION_9_10
            )
            .build()
        try {
            val loaded = runBlocking { room.calendarEventDao().getById(1L) }
            assertNotNull("迁移后旧数据可读", loaded)
            assertEquals("会议", loaded?.title)
            assertEquals("isLocal 默认 false", false, loaded?.isLocal)

            // App 自建日程（isLocal=1）可写
            val localId = runBlocking {
                room.calendarEventDao().insertIgnore(
                    CalendarEvent(
                        title = "自建", startAt = 20_000, endAt = 21_000,
                        sourceEventId = -1, sourceCalendarId = CalendarEvent.SOURCE_CALENDAR_ID_LOCAL,
                        isLocal = true
                    )
                )
            }
            val local = runBlocking { room.calendarEventDao().getById(localId) }
            assertEquals("isLocal 可写", true, local?.isLocal)

            // pruneOutside 不裁剪 App 自建日程（本地行超出窗口仍保留）
            runBlocking { room.calendarEventDao().pruneOutside(0L, 10_000L) }
            assertNotNull(
                "本地行超出窗口不应被裁剪",
                runBlocking { room.calendarEventDao().getById(localId) }
            )

            // v8→v9 已移除推送相关两列
            val columns = tableColumns(room)
            assertFalse("syncToSystem 列应已移除", columns.contains("syncToSystem"))
            assertFalse("systemEventId 列应已移除", columns.contains("systemEventId"))
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate8To9_dropsLocalColumns_realRoomOpen_validatesSchema() {
        // 手工建 v8 库（含 isLocal/syncToSystem/systemEventId 列，一条 App 自建日程 + 一条导入日程），
        // Room 走 MIGRATION_8_9 + MIGRATION_9_10 打开到 v10：数据保留、两列移除、新实体读写正常、唯一索引仍生效。
        val db = openDatabaseAtVersion(8)
        db.execSQL(createV5Reminders)
        db.execSQL(createTriggerRecords)
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
                "`isLocal` INTEGER NOT NULL, " +
                "`syncToSystem` INTEGER NOT NULL, " +
                "`systemEventId` INTEGER, " +
                "`reminded` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, " +
                "`permanentlyDeleted` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_events_sourceEventId_startAt` " +
                "ON `calendar_events` (`sourceEventId`, `startAt`)"
        )
        // 一条 App 自建并已推送的日程（isLocal=1, syncToSystem=1, systemEventId=99）
        db.execSQL(
            "INSERT INTO calendar_events (title, description, location, startAt, endAt, allDay, " +
                "sourceEventId, sourceCalendarId, isLocal, syncToSystem, systemEventId, reminded, " +
                "deleted, permanentlyDeleted, createdAt) " +
                "VALUES ('自建', NULL, NULL, 1000, 2000, 0, -1, -1, 1, 1, 99, 0, 0, 0, 1000)"
        )
        // 一条系统导入日程
        db.execSQL(
            "INSERT INTO calendar_events (title, description, location, startAt, endAt, allDay, " +
                "sourceEventId, sourceCalendarId, isLocal, syncToSystem, systemEventId, reminded, " +
                "deleted, permanentlyDeleted, createdAt) " +
                "VALUES ('导入', NULL, NULL, 5000, 6000, 0, 7, 1, 0, 0, NULL, 0, 0, 0, 2000)"
        )
        db.close()

        val room = Room.databaseBuilder(context, ReminderDatabase::class.java, dbName)
            .addMigrations(
                ReminderDatabase.MIGRATION_8_9,
                ReminderDatabase.MIGRATION_9_10
            )
            .build()
        try {
            // 数据保留（含 id、isLocal 标记），系统推送相关列已移除
            val local = runBlocking { room.calendarEventDao().getById(1L) }
            assertNotNull("App 自建日程迁移后应保留", local)
            assertEquals("自建", local?.title)
            assertEquals("isLocal 保留", true, local?.isLocal)
            assertEquals(
                "sourceCalendarId 本地哨兵保留", CalendarEvent.SOURCE_CALENDAR_ID_LOCAL,
                local?.sourceCalendarId
            )
            val imported = runBlocking { room.calendarEventDao().getById(2L) }
            assertNotNull("导入日程迁移后应保留", imported)
            assertEquals("导入", imported?.title)

            val columns = tableColumns(room)
            assertFalse("syncToSystem 列应已移除", columns.contains("syncToSystem"))
            assertFalse("systemEventId 列应已移除", columns.contains("systemEventId"))

            // 新实体可写可读
            val inserted = runBlocking {
                room.calendarEventDao().insertIgnore(
                    CalendarEvent(
                        title = "新自建", startAt = 10_000, endAt = 11_000,
                        sourceEventId = -2, sourceCalendarId = CalendarEvent.SOURCE_CALENDAR_ID_LOCAL,
                        isLocal = true
                    )
                )
            }
            assertEquals(
                "迁移后可新建 App 自建日程", "新自建",
                runBlocking { room.calendarEventDao().getById(inserted) }?.title
            )

            // 唯一索引仍生效：重复 (sourceEventId, startAt) 被 IGNORE
            val duplicateId = runBlocking {
                room.calendarEventDao().insertIgnore(
                    CalendarEvent(
                        title = "重复", startAt = 10_000, endAt = 11_000,
                        sourceEventId = -2, sourceCalendarId = CalendarEvent.SOURCE_CALENDAR_ID_LOCAL,
                        isLocal = true
                    )
                )
            }
            assertEquals("重复键应被忽略（返回 -1）", -1L, duplicateId)
        } finally {
            room.close()
        }
    }

    /** calendar_events 当前表结构的列名列表（用 PRAGMA 读取，验证列增减）。 */
    private fun tableColumns(room: ReminderDatabase): List<String> {
        val db = room.openHelper.readableDatabase
        return db.query("PRAGMA table_info(calendar_events)").use { cursor ->
            val names = ArrayList<String>()
            val nameCol = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) names.add(cursor.getString(nameCol))
            names
        }
    }

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
