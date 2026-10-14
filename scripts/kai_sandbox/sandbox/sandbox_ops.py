#!/usr/bin/env python3
"""
Kai Sandbox - Sandbox Operations (Linux sandbox execution)
Run in Kai sandbox via: python3 sandbox_ops.py <json_args>
"""

import json
import sys
import os
import subprocess
import time
import tempfile
import shutil

def execute_command(command, args=None, cwd=None, env=None, timeout=60):
    """Execute command in Kai sandbox via run-as"""
    if args is None:
        args = []
    
    cmd = [command] + args
    cmd_str = ' '.join([f'"{arg}"' for arg in cmd])
    
    if cwd:
        cmd_str = f"cd {cwd} && {cmd_str}"
    
    if env:
        env_str = ' '.join([f'{k}={v}' for k, v in env.items()])
        cmd_str = f"{env_str} {cmd_str}"
    
    runas_cmd = f'run-as com.inspiredandroid.kai sh -c "{cmd_str}"'
    
    try:
        start = time.time()
        result = subprocess.run(
            ["sh", "-c", runas_cmd],
            capture_output=True,
            text=True,
            timeout=timeout
        )
        duration = (time.time() - start) * 1000
        
        return {
            "exit_code": result.returncode,
            "stdout": result.stdout,
            "stderr": result.stderr,
            "timeout": False,
            "oom": False,
            "duration_ms": duration
        }
    except subprocess.TimeoutExpired:
        return {
            "exit_code": -1,
            "stdout": "",
            "stderr": "Command timed out",
            "timeout": True,
            "oom": False,
            "duration_ms": timeout * 1000
        }
    except Exception as e:
        return {
            "exit_code": -1,
            "stdout": "",
            "stderr": str(e),
            "timeout": False,
            "oom": False,
            "duration_ms": 0
        }

def exec_python(script, args=None, cwd=None, timeout=60):
    if args is None:
        args = []
    return execute_command("/data/data/com.inspiredandroid.kai/files/linux-sandbox/rootfs/usr/bin/python3", 
                          [script] + args, cwd, timeout=timeout)

def exec_node(script, args=None, cwd=None, timeout=60):
    if args is None:
        args = []
    return execute_command("/data/data/com.inspiredandroid.kai/files/linux-sandbox/rootfs/usr/bin/node", 
                          [script] + args, cwd, timeout=timeout)

def exec_shell(script, args=None, cwd=None, timeout=60):
    if args is None:
        args = []
    return execute_command("/data/data/com.inspiredandroid.kai/files/linux-sandbox/rootfs/bin/bash", 
                          ["-c", script] + args, cwd, timeout=timeout)

def read_file(path):
    try:
        result = subprocess.run(
            ["run-as", "com.inspiredandroid.kai", "cat", path],
            capture_output=True, text=True, timeout=10
        )
        if result.returncode == 0:
            return {"success": True, "content": result.stdout}
        return {"success": False, "error": result.stderr}
    except Exception as e:
        return {"success": False, "error": str(e)}

def write_file(path, content, mode=0o644):
    try:
        # Use heredoc to write file
        escaped = content.replace('"', '\\"').replace('$', '\\$').replace('`', '\\`')
        cmd = f'run-as com.inspiredandroid.kai sh -c \'cat > {path} << "EOF"\n{content}\nEOF\''
        result = subprocess.run(["sh", "-c", cmd], capture_output=True, text=True, timeout=10)
        return {"success": result.returncode == 0, "error": result.stderr if result.returncode != 0 else None}
    except Exception as e:
        return {"success": False, "error": str(e)}

def delete_file(path):
    result = subprocess.run(["run-as", "com.inspiredandroid.kai", "rm", "-f", path], 
                           capture_output=True, text=True, timeout=10)
    return {"success": result.returncode == 0, "error": result.stderr if result.returncode != 0 else None}

def list_dir(path):
    try:
        result = subprocess.run(
            ["run-as", "com.inspiredandroid.kai", "find", path, "-maxdepth", "1", 
             "-printf", "%p|%s|%Y|%m\n"],
            capture_output=True, text=True, timeout=10
        )
        files = []
        for line in result.stdout.strip().split('\n'):
            if not line:
                continue
            parts = line.split('|')
            if len(parts) >= 4:
                files.append({
                    "path": parts[0],
                    "size": int(parts[1]),
                    "is_dir": parts[2] == "d",
                    "mode": parts[3]
                })
        return {"success": True, "files": files}
    except Exception as e:
        return {"success": False, "error": str(e)}

def make_dir(path, mode=0o755):
    result = subprocess.run(["run-as", "com.inspiredandroid.kai", "mkdir", "-p", path], 
                           capture_output=True, text=True, timeout=10)
    return {"success": result.returncode == 0, "error": result.stderr if result.returncode != 0 else None}

def remove_dir(path):
    result = subprocess.run(["run-as", "com.inspiredandroid.kai", "rm", "-rf", path], 
                           capture_output=True, text=True, timeout=10)
    return {"success": result.returncode == 0, "error": result.stderr if result.returncode != 0 else None}

def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: python3 sandbox_ops.py '<json_args>'"}))
        return
    
    try:
        args = json.loads(sys.argv[1])
    except json.JSONDecodeError:
        print(json.dumps({"error": "Invalid JSON args"}))
        return
    
    action = args.get("action", "check")
    
    try:
        if action == "check":
            # Check if sandbox is accessible
            result = execute_command("echo", ["test"])
            if result["exit_code"] == 0 and "test" in result["stdout"]:
                print(json.dumps({"available": True, "sandbox": "ready"}))
            else:
                print(json.dumps({"available": False, "error": "Sandbox not accessible"}))
                
        elif action == "exec":
            command = args.get("command", "")
            args_list = args.get("args", [])
            cwd = args.get("cwd")
            env = args.get("env")
            timeout = args.get("timeout", 60)
            result = execute_command(command, args_list, cwd, None, timeout)
            print(json.dumps(result))
            
        elif action == "python":
            script = args.get("script", "")
            args_list = args.get("args", [])
            cwd = args.get("cwd")
            timeout = args.get("timeout", 60)
            result = exec_python(script, args, cwd, timeout)
            print(json.dumps(result))
            
        elif action == "node":
            script = args.get("script", "")
            args_list = args.get("args", [])
            cwd = args.get("cwd")
            timeout = args.get("timeout", 60)
            result = exec_node(script, args, cwd, timeout)
            print(json.dumps(result))
            
        elif action == "shell":
            script = args.get("script", "")
            args_list = args.get("args", [])
            cwd = args.get("cwd")
            timeout = args.get("timeout", 60)
            result = exec_shell(script, args, cwd, timeout)
            print(json.dumps(result))
            
        elif action == "read_file":
            path = args.get("path", "")
            result = read_file(path)
            print(json.dumps(result))
            
        elif action == "write_file":
            path = args.get("path", "")
            content = args.get("content", "")
            mode = args.get("mode", 0o644)
            result = write_file(path, content, mode)
            print(json.dumps(result))
            
        elif action == "delete_file":
            path = args.get("path", "")
            result = delete_file(path)
            print(json.dumps(result))
            
        elif action == "list_dir":
            path = args.get("path", "/data/data/com.inspiredandroid.kai/files")
            result = list_dir(path)
            print(json.dumps(result))
            
        elif action == "make_dir":
            path = args.get("path", "")
            mode = args.get("mode", 0o755)
            result = make_dir(path, mode)
            print(json.dumps(result))
            
        elif action == "remove_dir":
            path = args.get("path", "")
            result = remove_dir(path)
            print(json.dumps(result))
            
        else:
            print(json.dumps({"error": f"Unknown action: {action}"}))
            
    except Exception as e:
        print(json.dumps({"error": str(e)}))

if __name__ == "__main__":
    import json
    import sys
    import os
    import subprocess
    import json
    main()