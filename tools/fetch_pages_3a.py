# -*- coding: utf-8 -*-
"""从国家中小学智慧教育平台 CDN 批量下载译林三年级上册逐页图。

用法：python tools/fetch_pages_3a.py
输出：data/english/pages_img/NNN.jpg（NNN = 教材页序，001 起）
遇到连续 3 个 404 自动停止（页数不足 90 时兜底）。
"""
import pathlib
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "data" / "english" / "pages_img"
OUT.mkdir(parents=True, exist_ok=True)

DOC_ID = "aa2275a3-f969-c5fe-1c2c-d08b92223b67"
TS = "1756475802034"
HOSTS = ["r1-ndr", "r2-ndr", "r3-ndr"]
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")


def fetch(no: int) -> bool:
    name = "%03d.jpg" % no
    dst = OUT / name
    if dst.exists() and dst.stat().st_size > 20000:
        return True
    for host in HOSTS:
        url = ("https://%s.ykt.cbern.com.cn/edu_product/esp/assets/%s.t"
               "/zh-CN/%s/transcode/image/%d.jpg" % (host, DOC_ID, TS, no))
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            if len(data) > 20000:
                dst.write_bytes(data)
                print("ok  %s (%d KB)" % (name, len(data) // 1024), flush=True)
                return True
        except Exception as e:
            print("..  %s %s %s" % (name, host, type(e).__name__), flush=True)
            time.sleep(1)
    return False


miss = 0
n = 1
while miss < 3 and n <= 120:
    if fetch(n):
        miss = 0
    else:
        miss += 1
    n += 1
done = len(list(OUT.glob("*.jpg")))
print("完成，共 %d 页 -> %s" % (done, OUT), flush=True)
sys.exit(0 if done >= 80 else 2)
