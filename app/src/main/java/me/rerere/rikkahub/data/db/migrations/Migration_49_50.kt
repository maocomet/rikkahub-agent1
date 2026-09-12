package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v50 adds the Cat Garden Space tables. Purely additive: no existing table, column or index is
 * touched, so an upgraded install keeps every chat, setting and message row.
 *
 * The index names below are Room's auto-generated `index_<table>_<columns>` names, not hand-picked
 * ones. A migration that creates the right index under a different name fails Room's schema
 * validation at open time, so these strings must stay in lockstep with `space/SpaceEntities.kt`.
 */

internal const val SPACE_V50_POSTS_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `space_posts` (" +
        "`post_id` TEXT NOT NULL, " +
        "`author_kind` TEXT NOT NULL, " +
        "`author_id` TEXT NOT NULL, " +
        "`content` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`origin_depth` INTEGER NOT NULL, " +
        "PRIMARY KEY(`post_id`))"

internal const val SPACE_V50_LIKES_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `space_likes` (" +
        "`post_id` TEXT NOT NULL, " +
        "`actor_kind` TEXT NOT NULL, " +
        "`actor_id` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`post_id`, `actor_kind`, `actor_id`), " +
        "FOREIGN KEY(`post_id`) REFERENCES `space_posts`(`post_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"

internal const val SPACE_V50_COMMENTS_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `space_comments` (" +
        "`comment_id` TEXT NOT NULL, " +
        "`post_id` TEXT NOT NULL, " +
        "`author_kind` TEXT NOT NULL, " +
        "`author_id` TEXT NOT NULL, " +
        "`content` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`origin_depth` INTEGER NOT NULL, " +
        "PRIMARY KEY(`comment_id`), " +
        "FOREIGN KEY(`post_id`) REFERENCES `space_posts`(`post_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"

internal const val SPACE_V50_NOTIFICATIONS_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `space_notifications` (" +
        "`notification_id` TEXT NOT NULL, " +
        "`recipient_kind` TEXT NOT NULL, " +
        "`recipient_id` TEXT NOT NULL, " +
        "`actor_kind` TEXT NOT NULL, " +
        "`actor_id` TEXT NOT NULL, " +
        "`type` TEXT NOT NULL, " +
        "`post_id` TEXT NOT NULL, " +
        "`comment_id` TEXT, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`origin_depth` INTEGER NOT NULL, " +
        "`read_at_ms` INTEGER, " +
        "`consumed_at_ms` INTEGER, " +
        "PRIMARY KEY(`notification_id`), " +
        "FOREIGN KEY(`post_id`) REFERENCES `space_posts`(`post_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"

internal val SPACE_V50_INDEX_SQL = listOf(
    "CREATE INDEX IF NOT EXISTS `index_space_posts_created_at_ms` " +
        "ON `space_posts` (`created_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_space_posts_author_kind_author_id_created_at_ms` " +
        "ON `space_posts` (`author_kind`, `author_id`, `created_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_space_likes_post_id` " +
        "ON `space_likes` (`post_id`)",
    "CREATE INDEX IF NOT EXISTS `index_space_likes_actor_kind_actor_id_created_at_ms` " +
        "ON `space_likes` (`actor_kind`, `actor_id`, `created_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_space_comments_post_id_created_at_ms` " +
        "ON `space_comments` (`post_id`, `created_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_space_comments_author_kind_author_id_created_at_ms` " +
        "ON `space_comments` (`author_kind`, `author_id`, `created_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_space_notifications_recipient_kind_recipient_id_created_at_ms` " +
        "ON `space_notifications` (`recipient_kind`, `recipient_id`, `created_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_space_notifications_recipient_kind_recipient_id_read_at_ms` " +
        "ON `space_notifications` (`recipient_kind`, `recipient_id`, `read_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_space_notifications_post_id` " +
        "ON `space_notifications` (`post_id`)",
)

/** Additive Cat Garden schema. Existing rows in every prior table are untouched. */
val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(SPACE_V50_POSTS_TABLE_SQL)
        db.execSQL(SPACE_V50_LIKES_TABLE_SQL)
        db.execSQL(SPACE_V50_COMMENTS_TABLE_SQL)
        db.execSQL(SPACE_V50_NOTIFICATIONS_TABLE_SQL)
        SPACE_V50_INDEX_SQL.forEach(db::execSQL)
    }
}
