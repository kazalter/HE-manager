from sqlalchemy import create_engine, event
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
import os

# SQLite database file path (absolute path based on this file's location)
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SQLALCHEMY_DATABASE_URL = f"sqlite:///{os.path.join(BASE_DIR, 'library.db')}"

engine = create_engine(
    SQLALCHEMY_DATABASE_URL, 
    connect_args={"check_same_thread": False},
    pool_size=20,
    max_overflow=30,
    pool_timeout=60
)

@event.listens_for(engine, "connect")
def set_sqlite_pragma(dbapi_connection, connection_record):
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA foreign_keys=ON")
    # WAL lets readers and the writer run concurrently. Without it, the long-running
    # X-import thread blocks unrelated writes (login token INSERTs, etc.) and they
    # fail instantly with "database is locked".
    cursor.execute("PRAGMA journal_mode=WAL")
    # If two writers do collide, wait up to 5s instead of failing immediately.
    cursor.execute("PRAGMA busy_timeout=5000")
    cursor.execute("PRAGMA synchronous=NORMAL")
    # WAL 自动 checkpoint 阈值：默认 1000 页（≈4MB）才合并回主库。我们调到 200
    # 页（≈800KB），缩短「WAL 里有未合并写入」的风险窗口——异常退出时丢失/损
    # 坏的可能数据量小一个数量级。代价是更频繁的小批量 I/O，对 SSD 微不足道。
    cursor.execute("PRAGMA wal_autocheckpoint=200")
    cursor.close()

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()

# Utility to get DB session
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
