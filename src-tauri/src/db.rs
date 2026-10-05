use serde::{Deserialize, Serialize};
use sqlx::sqlite::{SqlitePool, SqlitePoolOptions};
use std::path::Path;

#[derive(Debug)]
pub struct DbState {
    pub pool: SqlitePool,
}

pub async fn init_db(db_path: &Path) -> Result<SqlitePool, sqlx::Error> {
    let db_url = format!("sqlite:{}?mode=rwc", db_path.display());
    let pool = SqlitePoolOptions::new()
        .max_connections(5)
        .connect(&db_url)
        .await?;

    // Migration: import history
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS import_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            source_path TEXT NOT NULL,
            dest_path TEXT NOT NULL,
            file_hash TEXT NOT NULL,
            file_size INTEGER NOT NULL,
            imported_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )",
    )
    .execute(&pool)
    .await?;

    // Migration: import rules
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS import_rules (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            folder_template TEXT NOT NULL DEFAULT '{date}',
            file_template TEXT NOT NULL DEFAULT '{original}',
            is_default INTEGER NOT NULL DEFAULT 0,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )",
    )
    .execute(&pool)
    .await?;

    // Insert default rule if none exists
    sqlx::query(
        "INSERT OR IGNORE INTO import_rules (name, folder_template, file_template, is_default)
         VALUES ('默认', '{date}', '{original}', 1)",
    )
    .execute(&pool)
    .await?;

    Ok(pool)
}

/// 一条导入历史 = 一次"真的把文件复制进归档"的留痕(跳过的不写, 见 importer.rs)。
///
/// `rename_all = "camelCase"`: 与 ImportProgress / DecisionRead / WriteSummary 等结构体
/// 统一, 前端不必为这两个结构体单独写 snake_case 字段名(Phase 6 之前前端零调用 → 无破坏面)。
#[derive(Debug, Serialize, Deserialize, sqlx::FromRow)]
#[serde(rename_all = "camelCase")]
pub struct ImportHistory {
    pub id: i64,
    pub source_path: String,
    pub dest_path: String,
    pub file_hash: String,
    pub file_size: i64,
    pub imported_at: String,
}

#[derive(Debug, Serialize, Deserialize, sqlx::FromRow)]
pub struct ImportRule {
    pub id: i64,
    pub name: String,
    pub folder_template: String,
    pub file_template: String,
    pub is_default: i64,
}

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error(transparent)]
    Sqlx(#[from] sqlx::Error),
}

impl serde::Serialize for Error {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: serde::ser::Serializer,
    {
        serializer.serialize_str(self.to_string().as_ref())
    }
}

/// 读一页导入历史。
///
/// `limit` 由调用方**显式传**(不再 `Option<u32>` + 默认 100): 默认值会把大导入静默截断,
/// 而 UI 需要"加载更多"; 总数由 [`count_history`] 单独给, 两者拼出"已显示 N / 共 M"。
///
/// ORDER BY 带 **id 兜底** 是必须的: `CURRENT_TIMESTAMP` 只有秒精度, 一次导入的几十行
/// 时间戳完全相同, 只按 imported_at 排序时分页顺序不确定(同一批记录会在页与页之间跳动)。
/// 抽成不依赖 `tauri::State` 的普通函数, 单测才能覆盖(见文件末 tests)。
async fn fetch_history(pool: &SqlitePool, limit: u32) -> Result<Vec<ImportHistory>, sqlx::Error> {
    sqlx::query_as::<_, ImportHistory>(
        "SELECT * FROM import_history ORDER BY imported_at DESC, id DESC LIMIT ?",
    )
    .bind(limit as i64)
    .fetch_all(pool)
    .await
}

/// 历史总条数(给 UI 显示"已显示 N / 共 M"与"加载更多"的可见性判断)
async fn count_history(pool: &SqlitePool) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM import_history")
        .fetch_one(pool)
        .await
}

#[tauri::command]
pub async fn get_import_history(
    state: tauri::State<'_, DbState>,
    limit: u32,
) -> Result<Vec<ImportHistory>, Error> {
    Ok(fetch_history(&state.pool, limit).await?)
}

#[tauri::command]
pub async fn count_import_history(state: tauri::State<'_, DbState>) -> Result<i64, Error> {
    Ok(count_history(&state.pool).await?)
}

#[tauri::command]
pub async fn get_rules(
    state: tauri::State<'_, DbState>,
) -> Result<Vec<ImportRule>, Error> {
    let rules =
        sqlx::query_as::<_, ImportRule>("SELECT * FROM import_rules ORDER BY id")
            .fetch_all(&state.pool)
            .await?;
    Ok(rules)
}

#[tauri::command]
pub async fn save_rule(
    state: tauri::State<'_, DbState>,
    name: String,
    folder_template: String,
    file_template: String,
) -> Result<i64, Error> {
    let result = sqlx::query(
        "INSERT OR REPLACE INTO import_rules (name, folder_template, file_template)
         VALUES (?, ?, ?)",
    )
    .bind(&name)
    .bind(&folder_template)
    .bind(&file_template)
    .execute(&state.pool)
    .await?;
    Ok(result.last_insert_rowid())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    /// 每个测试一个独立临时库(带 tag + pid, 与 importer.rs 的 test_root 同一套规矩):
    /// 测试并行跑也不会互相踩。
    fn test_db_dir(tag: &str) -> PathBuf {
        std::env::temp_dir().join(format!("imagefilter_db_{}_{}", tag, std::process::id()))
    }

    async fn fresh_pool(tag: &str) -> (SqlitePool, PathBuf) {
        let dir = test_db_dir(tag);
        std::fs::create_dir_all(&dir).unwrap();
        let db = dir.join("image_filter_test.db");
        let pool = init_db(&db).await.unwrap();
        (pool, dir)
    }

    /// 收尾: 必须先 close 再删目录(Windows 下句柄没放会删不掉)
    async fn cleanup(pool: SqlitePool, dir: PathBuf) {
        pool.close().await;
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 建表 + 播默认规则: 默认规则必须带 is_default=1(6.2 的 upsert 修复要保住它)
    #[tokio::test]
    async fn init_db_seeds_default_rule_with_is_default() {
        let (pool, dir) = fresh_pool("init").await;

        let rule = sqlx::query_as::<_, ImportRule>("SELECT * FROM import_rules WHERE name = ?")
            .bind("默认")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(rule.folder_template, "{date}");
        assert_eq!(rule.file_template, "{original}");
        assert_eq!(rule.is_default, 1, "默认规则被播成了 is_default=0");

        cleanup(pool, dir).await;
    }

    /// limit 显式生效 + 总数正确 + **同一秒内的多条按 id(插入序)倒序** ——
    /// 时间戳全部写成同一个值, 专门钉住 `ORDER BY imported_at DESC, id DESC` 的 id 兜底。
    #[tokio::test]
    async fn history_limit_count_and_insertion_order() {
        let (pool, dir) = fresh_pool("history").await;

        for name in ["a.jpg", "b.jpg", "c.jpg"] {
            sqlx::query(
                "INSERT INTO import_history (source_path, dest_path, file_hash, file_size, imported_at)
                 VALUES (?, ?, ?, ?, '2026-09-27 10:00:00')",
            )
            .bind(format!("E:/src/{}", name))
            .bind(format!("F:/dst/{}", name))
            .bind("deadbeef")
            .bind(1i64)
            .execute(&pool)
            .await
            .unwrap();
        }

        assert_eq!(count_history(&pool).await.unwrap(), 3);

        let page = fetch_history(&pool, 2).await.unwrap();
        assert_eq!(page.len(), 2, "limit 没生效");
        assert_eq!(page[0].source_path, "E:/src/c.jpg", "同一秒内没有按插入序倒序");
        assert_eq!(page[1].source_path, "E:/src/b.jpg");

        assert_eq!(fetch_history(&pool, 100).await.unwrap().len(), 3);

        cleanup(pool, dir).await;
    }
}


