#!/usr/bin/env python3
"""
Kai Sandbox - Browser Automation (Playwright)
Run in Kai sandbox via: python3 browser_ops.py <json_args>
"""

import json
import sys
import asyncio
from playwright.async_api import async_playwright

class BrowserSession:
    def __init__(self, config):
        self.config = config
        self.browser = None
        self.context = None
        self.page = None
        self.playwright = None
    
    async def initialize(self):
        self.playwright = await async_playwright().start()
        self.browser = await self.playwright.chromium.launch(
            headless=self.config.get("headless", True),
            args=self.config.get("args", [
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--single-process",
                "--no-zygote"
            ])
        )
        self.context = await self.browser.new_context(
            viewport={"width": self.config.get("viewport_width", 1280),
                     "height": self.config.get("viewport_height", 720)}
        )
        self.page = await self.context.new_page()
        self.page.set_default_timeout(self.config.get("timeout_ms", 30000))
        return True
    
    async def navigate(self, url, wait_ms=2000):
        try:
            await self.page.goto(url, wait_until="networkidle", timeout=self.config.get("timeout_ms", 30000))
            if wait_ms > 0:
                await asyncio.sleep(wait_ms / 1000)
            return await self._get_page_info()
        except Exception as e:
            return {"success": False, "error": str(e)}
    
    async def click(self, selector, timeout_ms=5000):
        try:
            await self.page.wait_for_selector(selector, timeout=timeout_ms)
            await self.page.click(selector)
            await asyncio.sleep(0.5)
            return {"success": True, "new_url": self.page.url}
        except Exception as e:
            return {"success": False, "error": str(e)}
    
    async def type_text(self, selector, text, delay_ms=50):
        try:
            await self.page.wait_for_selector(selector, timeout=5000)
            await self.page.fill(selector, "")
            await self.page.type(selector, text, delay=delay_ms)
            return {"success": True}
        except Exception as e:
            return {"success": False, "error": str(e)}
    
    async def get_content(self):
        return await self._get_page_info()
    
    async def evaluate(self, script):
        try:
            result = await self.page.evaluate(script)
            return {"success": True, "result": result}
        except Exception as e:
            return {"success": False, "error": str(e)}
    
    async def wait_for_selector(self, selector, timeout_ms=10000):
        try:
            await self.page.wait_for_selector(selector, timeout=timeout_ms)
            return {"success": True}
        except Exception as e:
            return {"success": False, "error": str(e)}
    
    async def screenshot(self, full_page=False):
        try:
            screenshot = await self.page.screenshot(full_page=full_page)
            import base64
            return {"success": True, "screenshot_base64": base64.b64encode(screenshot).decode()}
        except Exception as e:
            return {"success": False, "error": str(e)}
    
    async def get_cookies(self):
        return {"cookies": await self.context.cookies()}
    
    async def set_cookies(self, cookies):
        await self.context.add_cookies(cookies)
        return {"success": True}
    
    async def close(self):
        if self.page:
            await self.page.close()
        if self.context:
            await self.context.close()
        if self.browser:
            await self.browser.close()
        if self.playwright:
            await self.playwright.stop()
    
    async def _get_page_info(self):
        import base64
        html = await self.page.content()
        text = await self.page.evaluate("document.body.innerText")
        links = await self.page.evaluate("Array.from(document.querySelectorAll('a')).map(a => a.href)")
        images = await self.page.evaluate("Array.from(document.querySelectorAll('img')).map(i => i.src)")
        cookies = await self.context.cookies()
        screenshot = await self.page.screenshot(full_page=False)
        
        return {
            "success": True,
            "url": self.page.url,
            "title": await self.page.title(),
            "html": html,
            "text": text[:5000],
            "links": links,
            "images": images,
            "cookies": cookies,
            "screenshot_base64": base64.b64encode(await self.page.screenshot()).decode()
        }

async def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: python3 browser_ops.py '<json_args>'"}))
        return
    
    try:
        args = json.loads(sys.argv[1])
    except json.JSONDecodeError:
        print(json.dumps({"error": "Invalid JSON args"}))
        return
    
    action = args.get("action", "check")
    config = args.get("config", {})
    
    # Default config
    browser_config = {
        "engine": "playwright",
        "headless": config.get("headless", True),
        "viewport_width": config.get("viewport_width", 1280),
        "viewport_height": config.get("viewport_height", 720),
        "timeout_ms": config.get("timeout_ms", 30000),
        "args": config.get("args", [
            "--no-sandbox",
            "--disable-setuid-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--single-process",
            "--no-zygote"
        ])
    }
    
    session = BrowserSession(browser_config)
    initialized = False
    
    try:
        if args.get("action") == "check":
            try:
                await session.initialize()
                print(json.dumps({"available": True}))
            except Exception as e:
                print(json.dumps({"available": False, "error": str(e)}))
            return
        
        await session.initialize()
        initialized = True
        
        action = args.get("action")
        
        if action == "navigate":
            result = await session.navigate(args.get("url", "https://example.com"), 
                                          args.get("wait_ms", 2000))
        elif action == "click":
            result = await session.click(args.get("selector", ""), 
                                        args.get("timeout_ms", 5000))
        elif action == "type":
            result = await session.type_text(args.get("selector", ""), 
                                            args.get("text", ""),
                                            args.get("delay_ms", 50))
        elif action == "get_content":
            result = await session.get_content()
        elif action == "evaluate":
            result = await session.evaluate(args.get("script", ""))
        elif action == "wait_for_selector":
            result = await session.wait_for_selector(args.get("selector", ""), 
                                                    args.get("timeout_ms", 10000))
        elif action == "screenshot":
            result = await session.screenshot(args.get("full_page", False))
        elif action == "get_cookies":
            result = await session.get_cookies()
        elif action == "set_cookies":
            result = await session.set_cookies(args.get("cookies", []))
        elif action == "close":
            await session.close()
            print(json.dumps({"success": True}))
            return
        else:
            result = {"error": f"Unknown action: {action}"}
        
        print(json.dumps(result))
        
    except Exception as e:
        print(json.dumps({"error": str(e)}))
    finally:
        if initialized:
            await session.close()

if __name__ == "__main__":
    import json
    import sys
    import base64
    asyncio.run(main())