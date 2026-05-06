from datetime import datetime
import hashlib
import hmac
import secrets

from fastapi import Depends, Header, HTTPException, Query
from sqlalchemy.orm import Session

from . import models
from .database import get_db


PASSWORD_ITERATIONS = 260_000


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PASSWORD_ITERATIONS)
    return f"pbkdf2_sha256${PASSWORD_ITERATIONS}${salt.hex()}${digest.hex()}"


def verify_password(password: str, password_hash: str) -> bool:
    try:
        scheme, iterations, salt_hex, digest_hex = password_hash.split("$", 3)
        if scheme != "pbkdf2_sha256":
            return False
        digest = hashlib.pbkdf2_hmac(
            "sha256",
            password.encode("utf-8"),
            bytes.fromhex(salt_hex),
            int(iterations),
        )
        return hmac.compare_digest(digest.hex(), digest_hex)
    except Exception:
        return False


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def create_access_token(db: Session, user: models.User) -> str:
    token = secrets.token_urlsafe(32)
    db_token = models.AccessToken(token_hash=hash_token(token), user=user)
    db.add(db_token)
    db.commit()
    return token


def token_from_authorization(authorization: str | None) -> str | None:
    if not authorization:
        return None
    prefix = "Bearer "
    if not authorization.startswith(prefix):
        return None
    return authorization[len(prefix):].strip() or None


def get_current_user(
    authorization: str | None = Header(default=None),
    token: str | None = Query(default=None),
    db: Session = Depends(get_db),
) -> models.User:
    raw_token = token or token_from_authorization(authorization)
    if not raw_token:
        raise HTTPException(status_code=401, detail="Missing access token")

    db_token = (
        db.query(models.AccessToken)
        .filter(models.AccessToken.token_hash == hash_token(raw_token), models.AccessToken.revoked == False)
        .first()
    )
    if not db_token or not db_token.user or not db_token.user.is_active:
        raise HTTPException(status_code=401, detail="Invalid access token")

    db_token.last_used_at = datetime.utcnow()
    db.commit()
    return db_token.user


def require_admin(user: models.User = Depends(get_current_user)) -> models.User:
    if not user.is_admin:
        raise HTTPException(status_code=403, detail="Admin permission required")
    return user
