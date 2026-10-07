"""TMDB accepte une clé API v3 ou un jeton de lecture Bearer."""
import re
import httpx


def tmdb_client(key: str, timeout: float = 12) -> httpx.Client:
    key = key.strip().removeprefix("Bearer ").strip()
    if re.fullmatch(r"[a-fA-F0-9]{32}", key):
        return httpx.Client(timeout=timeout, params={"api_key": key})
    return httpx.Client(timeout=timeout, headers={"Authorization": f"Bearer {key}"})


def tmdb_status(key: str) -> str:
    if not key.strip():
        return "missing_key"
    try:
        with tmdb_client(key, timeout=8) as client:
            response = client.get("https://api.themoviedb.org/3/authentication")
        if response.status_code in {401, 403}:
            return "invalid_key"
        if response.status_code == 429:
            return "rate_limit"
        return "ok" if response.status_code == 200 and response.json().get("success") else "service_error"
    except (httpx.HTTPError, ValueError):
        return "network_error"
