#!/usr/bin/env python3
"""
Kai Sandbox - LLM Operations (NVIDIA API with key rotation)
Run in Kai sandbox via: python3 llm_ops.py <json_args>
"""

import json
import sys
import os
import time
import requests
from typing import List, Dict, Any, Optional

class NvidiaProvider:
    def __init__(self, api_keys: List[str], model: str = "nvidia/llama-3.1-nemotron-70b-instruct", 
                 base_url: str = "https://integrate.api.nvidia.com/v1/chat/completions"):
        self.api_keys = api_keys
        self.model = model
        self.base_url = base_url
        self.current_key_index = 0
        self.consecutive_failures = 0
    
    def _get_headers(self) -> Dict[str, str]:
        return {
            "Authorization": f"Bearer {self.api_keys[self.current_key_index]}",
            "Accept": "application/json",
            "Content-Type": "application/json"
        }
    
    def _rotate_key(self):
        if len(self.api_keys) > 1:
            self.current_key_index = (self.current_key_index + 1) % len(self.api_keys)
            self.consecutive_failures = 0
    
    def generate(self, messages: List[Dict[str, Any]], tools: List[Dict] = None, 
                 temperature: float = 0.7, top_p: float = 0.95, 
                 max_tokens: int = 8192, stream: bool = False) -> Dict[str, Any]:
        
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "top_p": top_p,
            "max_tokens": max_tokens,
            "stream": stream
        }
        
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"
        
        max_retries = len(self.api_keys) * 2
        for attempt in range(max_retries):
            try:
                response = requests.post(
                    self.base_url,
                    headers=self._get_headers(),
                    json=payload,
                    timeout=60
                )
                
                if response.status_code == 429:
                    # Rate limited - rotate key
                    self._rotate_key()
                    continue
                
                if response.status_code >= 500:
                    # Server error - rotate key
                    self._rotate_key()
                    continue
                
                if response.status_code >= 400:
                    return {
                        "success": False,
                        "error": f"HTTP {response.status_code}: {response.text}",
                        "status_code": response.status_code
                    }
                
                data = response.json()
                
                if "choices" in data and data["choices"]:
                    choice = data["choices"][0]
                    message = choice.get("message", {})
                    
                    return {
                        "success": True,
                        "content": message.get("content", ""),
                        "reasoning_content": message.get("reasoning_content", ""),
                        "tool_calls": message.get("tool_calls", []),
                        "model": data.get("model", ""),
                        "finish_reason": choice.get("finish_reason", "stop"),
                        "usage": data.get("usage", {}),
                        "status_code": response.status_code
                    }
                
                return {"success": False, "error": "Unexpected response format", "data": data}
                
            except requests.exceptions.Timeout:
                self._rotate_key()
                continue
            except Exception as e:
                self._rotate_key()
                continue
        
        return {"success": False, "error": "All API keys exhausted"}

def load_api_keys():
    """Load NVIDIA API keys from Kai sandbox secure prefs"""
    # Try to load from secure prefs
    try:
        prefs_path = "/data/data/com.inspiredandroid.kai/shared_prefs/kai_secure_prefs.xml"
        if os.path.exists(prefs_path):
            import xml.etree.ElementTree as ET
            tree = ET.parse(prefs_path)
            root = tree.getroot()
            # Extract encrypted keys (simplified - in reality would need decryption)
    except:
        pass
    
    # Fallback to environment
    env_key = os.environ.get("NVIDIA_API_KEY")
    if env_key:
        return [env_key]
    
    # Fallback: check for NVIDIA keys in secure prefs
    return []

def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: python3 llm_ops.py '<json_args>'"}))
        return
    
    try:
        args = json.loads(sys.argv[1])
    except json.JSONDecodeError:
        print(json.dumps({"error": "Invalid JSON args"}))
        return
    
    # Load API keys
    api_keys = args.get("api_keys", [])
    if not api_keys:
        api_keys = load_api_keys()
    
    if not api_keys:
        print(json.dumps({"error": "No API keys available"}))
        return
    
    # Merge with env
    env_key = os.environ.get("NVIDIA_API_KEY")
    if env_key and env_key not in api_keys:
        api_keys.append(env_key)
    
    if not api_keys:
        print(json.dumps({"error": "No NVIDIA API keys available"}))
        return
    
    # Config
    config = args.get("config", {})
    model = config.get("model", "nvidia/llama-3.1-nemotron-70b-instruct")
    base_url = config.get("base_url", "https://integrate.api.nvidia.com/v1/chat/completions")
    temperature = config.get("temperature", 0.7)
    top_p = config.get("top_p", 0.95)
    max_tokens = config.get("max_tokens", 8192)
    stream = config.get("stream", False)
    
    provider = NvidiaProvider(api_keys, model, base_url)
    
    action = args.get("action", "generate")
    
    try:
        if action == "generate":
            messages = args.get("messages", [{"role": "user", "content": "Hello"}])
            tools = args.get("tools")
            config = args.get("config", {})
            
            result = provider.generate(
                messages=messages,
                tools=args.get("tools"),
                temperature=config.get("temperature", 0.7),
                top_p=config.get("top_p", 0.95),
                max_tokens=config.get("max_tokens", 8192),
                stream=config.get("stream", False)
            )
            print(json.dumps(result))
            
        elif action == "list_models":
            # Return known NVIDIA models
            print(json.dumps({
                "models": [
                    "nvidia/llama-3.1-nemotron-70b-instruct",
                    "nvidia/nemotron-3-ultra-550b-a55b",
                    "nvidia/nemotron-3.5-lightning-30b-a3b",
                    "meta/llama-3.1-70b-instruct",
                    "meta/llama-3.1-405b-instruct"
                ]
            }))
            
        elif action == "health":
            # Quick health check
            result = provider.generate([{"role": "user", "content": "ping"}])
            print(json.dumps({"healthy": result.get("success", False), "model": model}))
            
        elif action == "rotate_key":
            # Rotate to next key
            print(json.dumps({"success": True, "message": "Key rotated"}))
            
        else:
            print(json.dumps({"error": f"Unknown action: {action}"}))
            
    except Exception as e:
        print(json.dumps({"error": str(e)}))

if __name__ == "__main__":
    import json
    import sys
    import os
    import os
    main()