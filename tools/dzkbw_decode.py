# -*- coding: utf-8 -*-
"""复刻 dzkbw.com 跳转页的 dzkbw() 解码函数，还原真实课本页 URL。"""
import sys
import urllib.parse


def dzkbw(s: str) -> str:
    b = 3 + len(s) % 4
    out = []
    d = len(s) * 7
    half = len(s) - len(s) // 2
    s = s[half:] + s[:half]
    for i in range(1, len(s) + 1):
        ch = chr(ord(s[i - 1]) + d % b + b // 2)
        out.append(ch)
        d = ord(out[-1])
    return "".join(out)


if __name__ == "__main__":
    enc = sys.argv[1]
    dec = urllib.parse.unquote(enc)
    print(dzkbw(dec))
