import unittest
from unittest.mock import patch, MagicMock
from datetime import datetime, timedelta
import tempfile

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app import auto_sync, models, database


class AutoSyncTest(unittest.TestCase):
    def setUp(self):
        # Create an in-memory SQLite engine and tables for clean testing
        self.engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False})
        models.Base.metadata.create_all(bind=self.engine)
        self.Session = sessionmaker(autocommit=False, autoflush=False, bind=self.engine)
        
        # Patch SessionLocal in database module to point to our in-memory SQLite DB
        self.patch_db = patch.object(database, "SessionLocal", self.Session)
        self.patch_db.start()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.patch_lock_dir = patch.object(auto_sync, "LOCK_DIR", self.temp_dir.name)
        self.patch_lock_dir.start()
        
    def tearDown(self):
        # Clean up any leftover active flags
        for key in list(auto_sync._active):
            source_type, source_id = key.split(":", 1)
            auto_sync._release_source(source_type, int(source_id))
        auto_sync._active.clear()
        auto_sync._source_lock_files.clear()
        self.patch_lock_dir.stop()
        self.temp_dir.cleanup()
        self.patch_db.stop()

    def test_update_schedule_and_restore(self):
        db = self.Session()
        try:
            # Create a mock WNACG source
            source = models.ExternalFavoriteSource(
                source_type="wnacg",
                name="Test Wnacg",
                favorites_url="https://www.wnacg.com/users-users_fav.html",
                cookie="session=xyz",
                download_root_path="/tmp/wnacg"
            )
            db.add(source)
            db.commit()
            db.refresh(source)
            
            source_id = source.id
            
            # 1. Test update_schedule: enable with 12 hours interval
            auto_sync.update_schedule("wnacg", source_id, enabled=True, interval_hours=12)
            
            db.refresh(source)
            self.assertTrue(source.auto_sync_enabled)
            self.assertEqual(source.auto_sync_interval_hours, 12)
            self.assertIsNotNone(source.auto_sync_next_run_at)
            
            # The next run should be scheduled roughly 12 hours from now
            diff = source.auto_sync_next_run_at - datetime.utcnow()
            self.assertTrue(timedelta(hours=11, minutes=59) < diff < timedelta(hours=12, minutes=1))
            
            # 2. Test restore schedule: overdue timers should get rescheduled to +60s
            source.auto_sync_next_run_at = datetime.utcnow() - timedelta(hours=5) # overdue
            db.commit()
            
            auto_sync._restore_schedules()
            
            db.refresh(source)
            diff_now = source.auto_sync_next_run_at - datetime.utcnow()
            self.assertTrue(diff_now < timedelta(seconds=65))
            
        finally:
            db.close()

    @patch("app.auto_sync.threading.Thread")
    def test_trigger_now(self, mock_thread):
        mock_thread.return_value.start = MagicMock()

        # Triggering a source should return True and mark it active
        res = auto_sync.trigger_now("wnacg", 1)
        self.assertTrue(res)
        self.assertTrue(auto_sync.is_source_active("wnacg", 1))
        
        # Triggering the same source again while active should return False to prevent overlap
        res_again = auto_sync.trigger_now("wnacg", 1)
        self.assertFalse(res_again)
        
        # Clean up active state
        auto_sync._release_source("wnacg", 1)

    def test_next_run_uses_short_retry_then_normal_interval(self):
        db = self.Session()
        try:
            finished_at = datetime.utcnow()
            retry_at = auto_sync._next_run_after_status(
                db, "wnacg", 1, "failed", finished_at, 24
            )
            self.assertTrue(
                timedelta(minutes=4, seconds=59)
                < retry_at - finished_at
                < timedelta(minutes=5, seconds=1)
            )

            db.add(models.AutoSyncLog(source_type="wnacg", source_id=1, status="failed"))
            db.commit()
            retry_at = auto_sync._next_run_after_status(
                db, "wnacg", 1, "partial", finished_at, 24
            )
            self.assertTrue(
                timedelta(minutes=14, seconds=59)
                < retry_at - finished_at
                < timedelta(minutes=15, seconds=1)
            )

            normal_at = auto_sync._next_run_after_status(
                db, "wnacg", 1, "success", finished_at, 24
            )
            self.assertTrue(
                timedelta(hours=23, minutes=59)
                < normal_at - finished_at
                < timedelta(hours=24, minutes=1)
            )
        finally:
            db.close()

    def test_restore_marks_stale_running_as_interrupted(self):
        db = self.Session()
        try:
            source = models.XImportSource(
                name="Test X",
                cookie="auth=xyz",
                download_root_path="/tmp/x",
                auto_sync_enabled=True,
                auto_sync_last_status="running",
                auto_sync_next_run_at=datetime.utcnow() - timedelta(hours=1),
            )
            db.add(source)
            db.commit()

            auto_sync._restore_schedules()

            db.refresh(source)
            self.assertEqual(source.auto_sync_last_status, "failed")
            self.assertIn("中断", source.auto_sync_last_message)
            self.assertTrue(source.auto_sync_next_run_at > datetime.utcnow())
        finally:
            db.close()


if __name__ == "__main__":
    unittest.main()
