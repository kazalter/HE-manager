import unittest
from unittest.mock import patch, MagicMock
from datetime import datetime, timedelta

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
        
    def tearDown(self):
        self.patch_db.stop()
        # Clean up any leftover active flags
        auto_sync._active.clear()

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

    @patch("app.auto_sync._run_wnacg")
    def test_trigger_now(self, mock_run_wnacg):
        # Triggering a source should return True and mark it active
        res = auto_sync.trigger_now("wnacg", 1)
        self.assertTrue(res)
        
        # Triggering the same source again while active should return False to prevent overlap
        res_again = auto_sync.trigger_now("wnacg", 1)
        self.assertFalse(res_again)
        
        # Clean up active state
        auto_sync._active.discard("wnacg:1")


if __name__ == "__main__":
    unittest.main()
