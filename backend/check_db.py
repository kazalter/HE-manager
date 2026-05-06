import sqlite3
import os

db_paths = [
    r'e:\HE manager\library.db',
    r'e:\HE manager\backend\app\library.db'
]

for db_path in db_paths:
    print(f"--- Checking {db_path} ---")
    if not os.path.exists(db_path):
        print("File does not exist.")
        continue
    
    try:
        conn = sqlite3.connect(db_path)
        cursor = conn.cursor()
        
        cursor.execute("SELECT COUNT(*) FROM folders")
        folder_count = cursor.fetchone()[0]
        print(f"Folders count: {folder_count}")
        
        cursor.execute("SELECT COUNT(*) FROM media")
        media_count = cursor.fetchone()[0]
        print(f"Media count: {media_count}")
        
        cursor.execute("SELECT status, path FROM folders")
        folders = cursor.fetchall()
        for status, path in folders:
            print(f"  Folder: {path} | Status: {status}")
            
        cursor.execute("SELECT media_type, COUNT(*) FROM media GROUP BY media_type")
        types = cursor.fetchall()
        for m_type, count in types:
            print(f"  Type: {m_type} | Count: {count}")
            
        conn.close()
    except Exception as e:
        print(f"Error checking DB: {e}")
