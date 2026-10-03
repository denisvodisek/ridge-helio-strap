"""Settings from the environment. One place, typed; nothing else reads os.environ."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    postgres_host: str = "127.0.0.1"
    postgres_port: int = 5432
    postgres_db: str = "strap"
    postgres_user: str = "strap"
    postgres_password: str = ""

    # Single owner (decision: one person, one device token per phone).
    owner_id: str = "00000000-0000-0000-0000-000000000001"
    owner_timezone: str = "UTC"
    # SHA-256 hex of the phone's bearer token. The token itself is never stored here.
    device_token_sha256: str = ""
    # Ask your data (DD2): the OpenRouter key stays on the server; the phone never sees it.
    openrouter_api_key: str = ""
    chat_model: str = "deepseek/deepseek-v4.1-flash"

    def conninfo(self) -> str:
        return (
            f"host={self.postgres_host} port={self.postgres_port} dbname={self.postgres_db} "
            f"user={self.postgres_user} password={self.postgres_password}"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()
