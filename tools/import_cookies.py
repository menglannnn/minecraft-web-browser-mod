"""把本机浏览器里已经登录好的 Cookie 导出来，喂给「网页方块」。

为什么需要它：MCEF 内嵌的 Chromium 用的是**独立 profile**（缓存在 mods/mcef-cache），
它不认识你系统浏览器里的登录状态。所以看 B 站之类会一直弹登录框。
把 Cookie 导进这个 profile 之后，网页方块里就自带登录了。

用法（在项目根目录）：

    python tools/import_cookies.py
        导出默认站点（B 站 / 腾讯视频 / 优酷 / 爱奇艺 / YouTube ...）的 Cookie

    python tools/import_cookies.py bilibili.com live.bilibili.com
        只导出指定域名

    python tools/import_cookies.py --all
        导出全部 Cookie（敏感！只在你清楚后果时用）

    python tools/import_cookies.py --list
        看看检测到了哪些浏览器 profile（可用 --browser 过滤）

    python tools/import_cookies.py --browser edge
        只读 Edge（可重复：--browser edge --browser chrome）；默认扫描全部检测到的

    python tools/import_cookies.py --from-json cookies.json
        直接转换浏览器扩展（Cookie-Editor / EditThisCookie）导出的 JSON

    python tools/import_cookies.py --out D:/somewhere/webscreen-cookies.json
        指定输出路径，默认写到 .minecraft/config/webscreen-cookies.json

导出后启动游戏即可，模组会自动读取并注入（也可以随时删掉这个文件，登录状态已经存进
MCEF 的持久化缓存了）。
"""

from __future__ import annotations

import argparse
import base64
import ctypes
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import time
from ctypes import wintypes
from pathlib import Path

IS_WINDOWS = sys.platform.startswith("win")

# --------------------------------------------------------------------- 默认站点
DEFAULT_DOMAINS = [
    "bilibili.com",
    "youtube.com",
    "v.qq.com",
    "youku.com",
    "iqiyi.com",
    "douyin.com",
    "twitch.tv",
]

# --------------------------------------------------------------------- DPAPI
if IS_WINDOWS:
    class _DATA_BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    _crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
    _kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _crypt32.CryptUnprotectData.argtypes = [
        ctypes.POINTER(_DATA_BLOB), ctypes.POINTER(wintypes.LPWSTR), ctypes.POINTER(_DATA_BLOB),
        ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(_DATA_BLOB),
    ]
    _crypt32.CryptUnprotectData.restype = wintypes.BOOL
    _kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    _kernel32.LocalFree.restype = ctypes.c_void_p


def dpapi_unprotect(data: bytes) -> bytes:
    """用当前用户的 DPAPI 密钥解密一段数据。"""
    if not IS_WINDOWS:
        raise RuntimeError("DPAPI 只在 Windows 上可用")
    buffer = ctypes.create_string_buffer(data, len(data))
    blob_in = _DATA_BLOB(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_char)))
    blob_out = _DATA_BLOB()
    if not _crypt32.CryptUnprotectData(
            ctypes.byref(blob_in), None, None, None, None, 0, ctypes.byref(blob_out)):
        raise OSError(f"CryptUnprotectData 失败（错误码 {ctypes.get_last_error()}）")
    try:
        return ctypes.string_at(blob_out.pbData, blob_out.cbData)
    finally:
        _kernel32.LocalFree(blob_out.pbData)


# --------------------------------------------------------------------- 浏览器探测
CHROMIUM_BROWSERS = [
    ("Chrome", Path(os.environ.get("LOCALAPPDATA", "")) / "Google" / "Chrome" / "User Data"),
    ("Edge", Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "Edge" / "User Data"),
    ("Brave", Path(os.environ.get("LOCALAPPDATA", "")) / "BraveSoftware" / "Brave-Browser" / "User Data"),
    ("Chromium", Path(os.environ.get("LOCALAPPDATA", "")) / "Chromium" / "User Data"),
    ("Vivaldi", Path(os.environ.get("LOCALAPPDATA", "")) / "Vivaldi" / "User Data"),
    ("360", Path(os.environ.get("LOCALAPPDATA", "")) / "360Chrome" / "Chrome" / "User Data"),
]

FIREFOX_ROOT = Path(os.environ.get("APPDATA", "")) / "Mozilla" / "Firefox" / "Profiles"


def chromium_profiles():
    """产出 (浏览器名, profile 目录)。"""
    for name, root in CHROMIUM_BROWSERS:
        if not root.is_dir():
            continue
        for entry in sorted(root.iterdir()):
            if not entry.is_dir():
                continue
            # Profile 目录名形如 Default / Profile 1 / Profile 2
            if entry.name != "Default" and not entry.name.startswith("Profile "):
                continue
            if cookie_db(entry) is not None:
                yield name, entry


def cookie_db(profile: Path):
    """Chrome 老版本放在 profile 根目录，新版本放在 Network/ 下。"""
    for candidate in (profile / "Network" / "Cookies", profile / "Cookies"):
        if candidate.is_file():
            return candidate
    return None


def firefox_profiles():
    if not FIREFOX_ROOT.is_dir():
        return
    for entry in sorted(FIREFOX_ROOT.iterdir()):
        if (entry / "cookies.sqlite").is_file():
            yield "Firefox", entry


def list_profiles(only=None):
    """列出检测到的 profile。only 是浏览器名的小写部分匹配列表，None 表示全部。"""
    def keep(name):
        return not only or any(token in name.lower() for token in only)

    found = False
    for name, profile in chromium_profiles():
        if not keep(name):
            continue
        found = True
        print(f"  [{name}] {profile}")
    for name, profile in firefox_profiles():
        if not keep(name):
            continue
        found = True
        print(f"  [{name}] {profile}")
    if not found:
        print("  没找到任何浏览器 profile")


# --------------------------------------------------------------------- 解密
class AppBoundError(RuntimeError):
    pass


def chromium_master_key(profile: Path):
    """从 user data 目录的 Local State 里取出 AES 主密钥。"""
    user_data = profile.parent
    local_state = user_data / "Local State"
    if not local_state.is_file():
        return None
    try:
        data = json.loads(local_state.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        return None
    os_crypt = data.get("os_crypt") or {}
    encrypted_key = os_crypt.get("encrypted_key")
    if not encrypted_key:
        return None
    blob = base64.b64decode(encrypted_key)
    if blob[:5] != b"DPAPI":
        return None
    try:
        return dpapi_unprotect(blob[5:])
    except Exception:
        return None


def decrypt_value(encrypted, key):
    from Crypto.Cipher import AES

    if not encrypted:
        return ""
    if isinstance(encrypted, str):
        encrypted = encrypted.encode("latin-1", "replace")

    prefix = encrypted[:3]
    if prefix in (b"v10", b"v11"):
        if key is None:
            raise RuntimeError("拿不到浏览器主密钥，无法解密 Cookie")
        nonce, ciphertext, tag = encrypted[3:15], encrypted[15:-16], encrypted[-16:]
        plain = AES.new(key, AES.MODE_GCM, nonce=nonce).decrypt_and_verify(ciphertext, tag)
        return plain.decode("utf-8", "replace")
    if prefix == b"v20":
        raise AppBoundError(
            "这条 Cookie 用的是 Chrome 127+ 的 App-Bound 加密，无法离线解密。"
        )
    # 很老的格式：整段就是 DPAPI 密文
    return dpapi_unprotect(encrypted).decode("utf-8", "replace")


# --------------------------------------------------------------------- 读取
CHROME_EPOCH_OFFSET_MS = 11644473600 * 1000


def chrome_expiry_to_ms(expires_utc):
    if not expires_utc:
        return None
    millis = int(expires_utc / 1000) - CHROME_EPOCH_OFFSET_MS
    return millis if millis > 0 else None


class LockedError(RuntimeError):
    """浏览器正开着，Cookie 库被独占锁住。"""


def copy_locked(src: Path, dst: Path):
    """带全套共享标志去复制一个可能被别的进程独占占用的文件。

    Windows 上 Chrome / Edge 开着的时候会把 Cookies 库以「不共享」的方式打开，
    普通 open() / shutil.copy2 会直接报 WinError 32。这里自己 CreateFile，
    至少能拿到最宽松的共享模式；如果对方真的是独占，就只能让用户先关掉浏览器。
    """
    GENERIC_READ = 0x80000000
    SHARE_ALL = 0x1 | 0x2 | 0x4
    OPEN_EXISTING = 3
    INVALID = ctypes.c_void_p(-1).value

    _kernel32.CreateFileW.restype = ctypes.c_void_p
    _kernel32.CreateFileW.argtypes = [
        wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p,
        wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p,
    ]
    _kernel32.ReadFile.argtypes = [
        ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD), ctypes.c_void_p,
    ]

    handle = _kernel32.CreateFileW(str(src), GENERIC_READ, SHARE_ALL, None, OPEN_EXISTING, 0, None)
    if handle == INVALID:
        error = ctypes.get_last_error()
        if error == 32:
            raise LockedError(str(src))
        raise OSError(f"打不开 {src}（错误码 {error}）")

    try:
        with open(dst, "wb") as out:
            buffer = ctypes.create_string_buffer(1 << 16)
            read = wintypes.DWORD()
            while True:
                if not _kernel32.ReadFile(handle, buffer, len(buffer), ctypes.byref(read), None):
                    raise OSError(f"读取 {src} 失败（错误码 {ctypes.get_last_error()}）")
                if read.value == 0:
                    break
                out.write(buffer.raw[:read.value])
    finally:
        _kernel32.CloseHandle(handle)


def matches(domain: str, wanted) -> bool:
    if not wanted:
        return True
    host = domain.lstrip(".").lower()
    return any(host == d or host.endswith("." + d) for d in wanted)


def read_chromium(profile: Path, wanted):
    db = cookie_db(profile)
    if db is None:
        return []

    # 密钥在 Local State 里，那个文件不锁，随时能读
    key = chromium_master_key(profile)

    temp_dir = tempfile.mkdtemp(prefix="webscreen-cookie-")
    temp_db = Path(temp_dir) / "Cookies"
    try:
        copy_locked(db, temp_db)
        # WAL 里可能还有没落盘的新写入，一起带上
        for suffix in ("-wal", "-shm"):
            sidecar = Path(str(db) + suffix)
            if sidecar.is_file():
                try:
                    copy_locked(sidecar, Path(str(temp_db) + suffix))
                except (OSError, LockedError):
                    pass

        connection = sqlite3.connect(f"file:{temp_db.as_posix()}?immutable=1", uri=True)
        try:
            rows = connection.execute(
                "SELECT host_key, name, encrypted_value, path, expires_utc, is_secure, is_httponly "
                "FROM cookies"
            ).fetchall()
        finally:
            connection.close()
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)

    cookies, app_bound = [], 0
    for host, name, encrypted, path, expires_utc, secure, http_only in rows:
        if not matches(host, wanted):
            continue
        try:
            value = decrypt_value(encrypted, key)
        except AppBoundError:
            app_bound += 1
            continue
        except Exception:
            continue
        if not value:
            continue
        cookies.append({
            "domain": host,
            "name": name,
            "value": value,
            "path": path or "/",
            "secure": bool(secure),
            "httpOnly": bool(http_only),
            "expires": chrome_expiry_to_ms(expires_utc),
        })
    if app_bound:
        print(f"    注意：有 {app_bound} 条 Cookie 是 App-Bound 加密，已跳过（见下面的说明）")
    return cookies


def read_firefox(profile: Path, wanted):
    """Firefox 的 Cookie 是明文存在 SQLite 里的，直接读就行。"""
    db = profile / "cookies.sqlite"
    temp_dir = tempfile.mkdtemp(prefix="webscreen-cookie-")
    temp_db = Path(temp_dir) / "cookies.sqlite"
    try:
        try:
            copy_locked(db, temp_db)
        except (OSError, LockedError):
            shutil.copy2(db, temp_db)
        connection = sqlite3.connect(f"file:{temp_db.as_posix()}?immutable=1", uri=True)
        try:
            rows = connection.execute(
                "SELECT host, name, value, path, expiry, isSecure, isHttpOnly FROM moz_cookies"
            ).fetchall()
        finally:
            connection.close()
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)

    cookies = []
    for host, name, value, path, expiry, secure, http_only in rows:
        if not matches(host, wanted) or not value:
            continue
        cookies.append({
            "domain": host,
            "name": name,
            "value": value,
            "path": path or "/",
            "secure": bool(secure),
            "httpOnly": bool(http_only),
            "expires": int(expiry * 1000) if expiry else None,
        })
    return cookies


# --------------------------------------------------------------------- 扩展导出的 JSON
def read_extension_json(path: Path, wanted):
    data = json.loads(path.read_text(encoding="utf-8", errors="replace"))
    if isinstance(data, dict):
        data = data.get("cookies", [])
    cookies = []
    for entry in data:
        if not isinstance(entry, dict):
            continue
        domain = str(entry.get("domain") or entry.get("host") or "")
        name = str(entry.get("name") or "")
        value = str(entry.get("value") or "")
        if not domain or not name:
            continue
        if not matches(domain, wanted):
            continue
        expires = entry.get("expires")
        if expires is None:
            seconds = entry.get("expirationDate")
            expires = int(seconds * 1000) if seconds else None
        cookies.append({
            "domain": domain,
            "name": name,
            "value": value,
            "path": str(entry.get("path") or "/"),
            "secure": bool(entry.get("secure", True)),
            "httpOnly": bool(entry.get("httpOnly", False)),
            "expires": int(expires) if expires else None,
        })
    return cookies


# --------------------------------------------------------------------- 输出
def minecraft_config_dir() -> Path:
    candidates = []
    if os.environ.get("APPDATA"):
        candidates.append(Path(os.environ["APPDATA"]) / ".minecraft" / "config")
    home = Path.home()
    candidates.append(home / ".minecraft" / "config")
    candidates.append(home / "AppData" / "Roaming" / ".minecraft" / "config")
    # 开发环境（gradle runClient）
    candidates.append(Path(__file__).resolve().parent.parent / "run" / "config")

    for candidate in candidates:
        if candidate.is_dir():
            return candidate
    return candidates[0]


def write_output(cookies, out_path: Path):
    payload = {
        "version": 1,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "count": len(cookies),
        "cookies": cookies,
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="导出本机浏览器的 Cookie 给「网页方块」用",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("domains", nargs="*", help="只导出这些域名（默认是内置的几个视频站）")
    parser.add_argument("--all", action="store_true", help="导出全部 Cookie（敏感）")
    parser.add_argument("--browser", metavar="NAME", action="append",
                        help="只读这些浏览器，可重复（如 --browser edge --browser chrome）；"
                             "默认扫描全部检测到的。名字不区分大小写，支持部分匹配")
    parser.add_argument("--list", action="store_true", help="列出检测到的浏览器 profile")
    parser.add_argument("--from-json", metavar="FILE", help="转换浏览器扩展导出的 JSON")
    parser.add_argument("--out", metavar="FILE", help="输出路径")
    args = parser.parse_args()

    if not IS_WINDOWS:
        print("这个脚本目前只支持 Windows（Chrome/Edge 的密钥保护依赖 DPAPI）。")
        print("其它系统可以用浏览器扩展 Cookie-Editor 导出 JSON，再用 --from-json 转换。")
        return 2

    # --browser 里给的名字，做小写部分匹配（"edge" 能命中 "Edge"，"chrom" 能命中 "Chrome"/"Chromium"）
    only = [b.lower() for b in args.browser] if args.browser else None

    def wanted_browser(name: str) -> bool:
        if not only:
            return True
        return any(token in name.lower() for token in only)

    if args.list:
        print("检测到的浏览器 profile：")
        list_profiles(only)
        return 0

    wanted = None if args.all else [d.lower() for d in (args.domains or DEFAULT_DOMAINS)]

    if args.from_json:
        source = Path(args.from_json)
        cookies = read_extension_json(source, wanted)
        origin = f"扩展导出文件 {source.name}"
    else:
        cookies = []
        origins = []
        locked = []
        matched_any = False
        for name, profile in chromium_profiles():
            if not wanted_browser(name):
                continue
            matched_any = True
            print(f"读取 [{name}] {profile.name} ...")
            try:
                got = read_chromium(profile, wanted)
            except LockedError:
                print("    读不了：Cookie 库被浏览器独占锁住了")
                locked.append(name)
                continue
            print(f"    命中 {len(got)} 条")
            if got:
                cookies.extend(got)
                origins.append(name)
        for name, profile in firefox_profiles():
            if not wanted_browser(name):
                continue
            matched_any = True
            print(f"读取 [{name}] {profile.name} ...")
            got = read_firefox(profile, wanted)
            print(f"    命中 {len(got)} 条")
            if got:
                cookies.extend(got)
                origins.append(name)
        origin = "/".join(dict.fromkeys(origins)) or "无"

        if only and not matched_any:
            print()
            print(f"没有找到名字匹配 {'、'.join(args.browser)} 的浏览器。")
            print("用 --list 看看这台机器上检测到了哪些（名字是 Chrome / Edge / Brave / Chromium /")
            print("Vivaldi / 360 / Firefox）。")
            return 1

    # 同名 Cookie 只留最后一条
    deduped = {}
    for cookie in cookies:
        deduped[(cookie["domain"], cookie["path"], cookie["name"])] = cookie
    cookies = list(deduped.values())

    if not cookies:
        print()
        if locked:
            print(f"「{'、'.join(locked)}」正开着，Cookie 库被独占锁住，读不到。")
            print("请**完全退出**浏览器（Edge/Chrome 关掉窗口后还会留在后台，")
            print("去任务管理器结束 msedge.exe / chrome.exe），然后重新运行本脚本。")
            print()
        print("没有导到任何 Cookie。其它可能的原因：")
        print("  1. 这台机器上没登录过目标站点；")
        print("  2. 浏览器用的是 Chrome 127+ 的 App-Bound 加密（v20），离线解不开。")
        print()
        print("App-Bound 的绕法（推荐第一种）：")
        print("  · 直接进游戏，走到屏幕前按 F8 打开「网页输入模式」，在方块上登录一次即可")
        print("    （登录状态会存进 MCEF 的持久化缓存，之后一直有效）；")
        print("  · 或者装个 Cookie-Editor / EditThisCookie 扩展导出 JSON，再执行：")
        print("    python tools/import_cookies.py --from-json cookies.json")
        return 1

    out_path = Path(args.out) if args.out else minecraft_config_dir() / "webscreen-cookies.json"
    write_output(cookies, out_path)

    print()
    print(f"来源：{origin}")
    print(f"导出 {len(cookies)} 条 Cookie -> {out_path}")
    print()
    print("提醒：这个文件等同于你的登录凭证，别分享、别提交到 git。")
    print("启动游戏后模组会自动注入；注入成功后其实可以把它删掉（登录状态已存进 MCEF 缓存）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
