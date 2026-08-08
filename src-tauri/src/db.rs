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

#[derive(Debug, Serialize, Deserialize, sqlx::FromRow)]
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

#[tauri::command]
pub async fn get_import_history(
    state: tauri::State<'_, DbState>,
    limit: Option<u32>,
) -> Result<Vec<ImportHistory>, Error> {
    let limit = limit.unwrap_or(100);
    let items = sqlx::query_as::<_, ImportHistory>(
        "SELECT * FROM import_history ORDER BY imported_at DESC LIMIT ?",
    )
    .bind(limit as i64)
    .fetch_all(&state.pool)
    .await?;
    Ok(items)
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


