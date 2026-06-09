import os
import sys
import subprocess
import paramiko

# Reconfigure stdout/stderr to UTF-8 to support printing unicode characters on Windows
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8')

# Configuration — read from .env file next to this script
def load_env():
    env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    if not os.path.exists(env_path):
        print(f"Error: .env file not found at {env_path}")
        print("Create it with: DEPLOY_HOST, DEPLOY_USER, DEPLOY_PASSWORD")
        sys.exit(1)
    env = {}
    with open(env_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            key, _, value = line.partition("=")
            env[key.strip()] = value.strip()
    return env

_env = load_env()
HOSTNAME = _env["DEPLOY_HOST"]
USERNAME = _env["DEPLOY_USER"]
PASSWORD = _env["DEPLOY_PASSWORD"]
REMOTE_ROOT = "/opt/stacks/he-manager"

def run_local_build(frontend_dir):
    print("==> Running local Vite build in Windows...")
    try:
        # Run npm run build in frontend folder
        result = subprocess.run(
            "npm run build", 
            shell=True, 
            cwd=frontend_dir, 
            check=True, 
            capture_output=True, 
            text=True,
            encoding="utf-8",
            errors="ignore"
        )
        print(result.stdout)
        print("Vite build completed successfully!")
        return True
    except subprocess.CalledProcessError as e:
        print("Vite build failed!")
        print("STDOUT:", e.stdout)
        print("STDERR:", e.stderr)
        return False

def upload_dir_sftp(sftp, local_dir, remote_dir, ignore_files=None, ignore_dirs=None):
    if ignore_files is None:
        ignore_files = []
    if ignore_dirs is None:
        ignore_dirs = []
        
    try:
        sftp.mkdir(remote_dir)
        print(f"Created remote directory: {remote_dir}")
    except IOError:
        pass  # already exists
        
    for item in os.listdir(local_dir):
        if item in ignore_files or item in ignore_dirs:
            continue
            
        local_path = os.path.join(local_dir, item)
        remote_path = os.path.join(remote_dir, item).replace('\\', '/')
        
        if os.path.isdir(local_path):
            if item in ignore_dirs:
                continue
            upload_dir_sftp(sftp, local_path, remote_path, ignore_files, ignore_dirs)
        else:
            print(f"Uploading {local_path} -> {remote_path}")
            sftp.put(local_path, remote_path)

def clear_remote_dir(ssh, path):
    # Safe remove of directory contents
    print(f"==> Clearing remote directory: {path}")
    ssh.exec_command(f"rm -rf {path}")
    ssh.exec_command(f"mkdir -p {path}")

def deploy():
    # Resolve local paths relative to this script
    script_dir = os.path.dirname(os.path.abspath(__file__))
    frontend_dir = os.path.join(script_dir, "frontend")
    frontend_dist = os.path.join(frontend_dir, "dist")
    backend_app_dir = os.path.join(script_dir, "backend", "app")
    
    # 1. Run local build
    if not run_local_build(frontend_dir):
        print("Aborting deployment due to build failure.")
        return
        
    # Connect SSH to clear directories
    print("\n==> Connecting to Linux SSH...")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        ssh.connect(HOSTNAME, username=USERNAME, password=PASSWORD, timeout=15)
    except Exception as e:
        print(f"SSH connection failed: {e}")
        return
        
    try:
        # Clear out dist directory on server
        clear_remote_dir(ssh, f"{REMOTE_ROOT}/frontend/dist")
    except Exception as e:
        print(f"Failed to clear remote folder: {e}")
        ssh.close()
        return

    # Connect SFTP for uploading
    print("\n==> Connecting to Linux SFTP...")
    transport = paramiko.Transport((HOSTNAME, 22))
    try:
        transport.connect(username=USERNAME, password=PASSWORD)
        sftp = paramiko.SFTPClient.from_transport(transport)
    except Exception as e:
        print(f"SFTP connection failed: {e}")
        ssh.close()
        return
        
    try:
        # 2. Upload frontend dist
        print("\n==> Syncing Frontend Build (dist/)...")
        upload_dir_sftp(sftp, frontend_dist, f"{REMOTE_ROOT}/frontend/dist")
        
        # 3. Upload backend source files (excluding library.db and caches)
        print("\n==> Syncing Backend Source (backend/app/)...")
        ignore_files = ["library.db", "library.db.corrupt-original", "library.db.corrupt-backup-20260521-1028"]
        ignore_dirs = ["__pycache__", ".pytest_cache"]
        upload_dir_sftp(
            sftp, 
            backend_app_dir, 
            f"{REMOTE_ROOT}/backend/app", 
            ignore_files=ignore_files, 
            ignore_dirs=ignore_dirs
        )
        print("\nSync completed successfully!")
    except Exception as e:
        print(f"Error during file sync: {e}")
        sftp.close()
        transport.close()
        ssh.close()
        return
        
    # Close SFTP connection
    sftp.close()
    transport.close()
    
    # 4. Restart Docker container via SSH
    print("\n==> Restarting Docker Containers on Linux...")
    try:
        # Safe stdout/stderr redirect and printing
        stdin, stdout, stderr = ssh.exec_command(f"cd {REMOTE_ROOT} && docker compose up -d --build")
        
        # Wait for command completion
        exit_status = stdout.channel.recv_exit_status()
        out_logs = stdout.read().decode('utf-8', errors='ignore')
        err_logs = stderr.read().decode('utf-8', errors='ignore')
        
        if out_logs:
            print(out_logs.strip())
        if err_logs:
            print(err_logs.strip())
            
        if exit_status == 0:
            print("\n🎉 Deployment completed and containers restarted successfully!")
        else:
            print(f"\n❌ Docker compose restart exited with status: {exit_status}")
            
    except Exception as e:
        print(f"Failed to restart docker: {e}")
    finally:
        ssh.close()

if __name__ == "__main__":
    deploy()
