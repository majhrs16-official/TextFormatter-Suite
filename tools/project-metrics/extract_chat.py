#!/usr/bin/env python3
"""
Etapa B: parser de los chats -> tabla de eventos + metricas diarias.

Entrada: un UNICO fichero `chat.log` (exportacion de las sesiones de OpenCode).
Soporta JSONL (una sesion por linea), JSON unico o JSON en lista. Ver consolidate.py.

Adapta los dos esquemas de mensaje vistos:
  A) export aplanado : {id,time,type,text} | {id,time,type,agent,model,content[],tokens}
  B) formato raw     : {info, parts[]}

Salida (en PM_DATA):
  - chat_events.jsonl : un evento por linea (para unir con commits, Etapa C)
  - chat_daily.csv    : date + metricas de la narrativa (dias sin actividad = 0)
  - chat_long.csv     : date,session,metric,value
  - chat_commits.csv  : commit_hash,date,session,message  (de comandos `git commit`)
"""
import csv
import json
import os
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone, date as _date, timedelta

import paths
from consolidate import iter_sessions

OUTDIR = paths.DATA
SRC = sys.argv[1] if len(sys.argv) > 1 else paths.CHAT_LOG


def part_iter(msg):
    if isinstance(msg.get("content"), list):
        return msg["content"]
    if isinstance(msg.get("parts"), list):
        return msg["parts"]
    return []


def msg_time(msg):
    t = msg.get("time") or (msg.get("info") or {}).get("time") or {}
    for k in ("created", "start", "completed"):
        v = t.get(k)
        if isinstance(v, (int, float)) and v > 1e11:
            return int(v)
    return None


def msg_type(msg):
    return msg.get("type") or (msg.get("info") or {}).get("role")


def get_input(part):
    st = part.get("state") or {}
    return st.get("input") or part.get("input") or {}


def get_status(part):
    st = part.get("state") or {}
    return st.get("status") or part.get("status")


def target_of(tool, inp):
    if not isinstance(inp, dict):
        return ""
    for k in ("filePath", "path", "command", "pattern", "url", "query"):
        v = inp.get(k)
        if isinstance(v, str) and v:
            return v if k == "command" else v.replace(os.path.expanduser("~"), "~")
    return ""


COMMIT_RE = re.compile(r"\[[^\]\n]*?\b([0-9a-f]{7,40})\b\]")
MSG_RE = re.compile(r"""(?:-m|--message)[=\s]+["']([^"']{0,200})""")


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    if not os.path.exists(SRC):
        sys.exit(f"no existe el fichero de chats: {SRC}\n"
                 f"define PM_CHAT_LOG o genera uno con consolidate.py")

    events, tool_counter, commits = [], Counter(), []
    day = defaultdict(Counter)
    per_session = {}

    for sess in iter_sessions(SRC):
        info = sess.get("info") or {}
        name = (info.get("title") or info.get("id") or "sesion").strip()
        model = info.get("model")
        if isinstance(model, dict):
            model = model.get("id")
        msgs = sess.get("messages") or []
        sess_t = []
        for m in msgs:
            ts = msg_time(m)
            td = msg_type(m)
            if ts:
                sess_t.append(ts)
            dstr = datetime.fromtimestamp(ts / 1000, tz=timezone.utc).strftime("%Y-%m-%d") if ts else None
            mid = m.get("id") or (m.get("info") or {}).get("id")
            toks = m.get("tokens") or {}

            if td == "user":
                txt = m.get("text") or ""
                if txt:
                    events.append({"ts": ts, "date": dstr, "session": name, "kind": "user",
                                   "msg": mid, "chars": len(txt), "title": name, "model": model})
                    day[dstr]["user_prompts"] += 1
                    day[dstr]["user_chars"] += len(txt)
            elif td == "compaction":
                events.append({"ts": ts, "date": dstr, "session": name, "kind": "compaction",
                               "msg": mid, "chars": len(m.get("summary") or "")})
                day[dstr]["compactions"] += 1
            elif td == "assistant":
                if isinstance(toks, dict) and toks:
                    day[dstr]["tokens_input"] += toks.get("input", 0) or 0
                    day[dstr]["tokens_output"] += toks.get("output", 0) or 0
                    day[dstr]["tokens_reasoning"] += toks.get("reasoning", 0) or 0
                    cache = toks.get("cache") or {}
                    day[dstr]["cache_read"] += cache.get("read", 0) or 0
                    events.append({"ts": ts, "date": dstr, "session": name, "kind": "tokens",
                                   "msg": mid, "tokens": toks, "model": model})
                for part in part_iter(m):
                    pt = part.get("type")
                    pts = (part.get("time") or {}).get("created")
                    pdstr = datetime.fromtimestamp(pts / 1000, tz=timezone.utc).strftime("%Y-%m-%d") if pts else dstr
                    if pt == "reasoning":
                        txt = part.get("text") or ""
                        day[pdstr]["reasoning_blocks"] += 1
                        day[pdstr]["reasoning_chars"] += len(txt)
                        events.append({"ts": pts, "date": pdstr, "session": name, "kind": "reasoning",
                                       "msg": mid, "chars": len(txt)})
                    elif pt == "text":
                        txt = part.get("text") or ""
                        day[pdstr]["assistant_texts"] += 1
                        day[pdstr]["assistant_chars"] += len(txt)
                        events.append({"ts": pts, "date": pdstr, "session": name, "kind": "text",
                                       "msg": mid, "chars": len(txt)})
                    elif pt in ("tool", "tool.execution"):
                        tool = part.get("name") or part.get("tool") or "?"
                        status = get_status(part) or "?"
                        inp = get_input(part)
                        tgt = target_of(tool, inp)
                        tool_counter[tool] += 1
                        day[pdstr]["tool_calls"] += 1
                        day[pdstr][f"tool:{tool}"] += 1
                        if status == "error":
                            day[pdstr]["tool_errors"] += 1
                        events.append({"ts": pts, "date": pdstr, "session": name, "kind": "tool",
                                       "msg": mid, "tool": tool, "status": status, "target": tgt[:300]})
                        if tool in ("edit", "write", "patch"):
                            day[pdstr]["edits"] += 1
                            if tgt:
                                day[pdstr][f"file:{tgt}"] += 1
                        elif tool == "read":
                            day[pdstr]["reads"] += 1
                        elif tool in ("bash", "shell"):
                            day[pdstr]["commands"] += 1
                            cmd = (inp or {}).get("command") or ""
                            if "git commit" in cmd:
                                day[pdstr]["git_commits"] += 1
                                out_txt = ""
                                st = part.get("state") or {}
                                for c in (st.get("content") or []):
                                    if isinstance(c, dict):
                                        out_txt += c.get("text") or ""
                                mh = COMMIT_RE.search(out_txt)
                                mm = MSG_RE.search(cmd)
                                if mh:
                                    commits.append({
                                        "commit": mh.group(1), "date": pdstr, "session": name,
                                        "message": (mm.group(1) if mm else "").replace("\n", " ")[:160]})
                        elif tool in ("grep", "glob"):
                            day[pdstr]["searches"] += 1
        per_session[name] = {
            "modelo": model, "mensajes": len(msgs),
            "desde": datetime.fromtimestamp(min(sess_t) / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M") if sess_t else "?",
            "hasta": datetime.fromtimestamp(max(sess_t) / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M") if sess_t else "?",
        }

    with open(os.path.join(OUTDIR, "chat_events.jsonl"), "w") as f:
        for e in sorted(events, key=lambda x: (x.get("ts") or 0)):
            f.write(json.dumps(e, ensure_ascii=False) + "\n")

    METRICS = ["user_prompts", "assistant_texts", "reasoning_blocks", "tool_calls",
               "tool_errors", "edits", "reads", "commands", "searches", "git_commits",
               "compactions", "tokens_input", "tokens_output", "tokens_reasoning",
               "cache_read", "user_chars", "assistant_chars", "reasoning_chars"]
    days = sorted(day)
    if days:
        span = []
        cur = _date.fromisoformat(days[0])
        end = _date.fromisoformat(days[-1])
        while cur <= end:
            ds = cur.isoformat()
            span.append([ds] + [day[ds].get(m, 0) if ds in day else 0 for m in METRICS])
            cur += timedelta(days=1)
        with open(os.path.join(OUTDIR, "chat_daily.csv"), "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["date"] + METRICS)
            w.writerows(span)

    with open(os.path.join(OUTDIR, "chat_long.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["date", "scope", "metric", "value"])
        for ds in days:
            for k, v in day[ds].items():
                w.writerow([ds, "chat", k, v])

    seen = {}
    for c in commits:
        seen.setdefault(c["commit"], c)
    with open(os.path.join(OUTDIR, "chat_commits.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["commit", "date", "session", "message"])
        for h, c in sorted(seen.items(), key=lambda kv: kv[1]["date"]):
            w.writerow([h, c["date"], c["session"], c["message"]])

    print(f"# fichero: {SRC}")
    print(f"# sesiones: {len(per_session)}  eventos: {len(events)}  "
          f"commits detectados en chat: {len(seen)}")
    print(f"# rango: {days[0] if days else '?'} -> {days[-1] if days else '?'}  "
          f"({len(days)} dias con actividad)")
    print("# herramientas:", dict(tool_counter.most_common()))
    print("\n# por sesion:")
    for n, s in sorted(per_session.items(), key=lambda kv: kv[1]["desde"]):
        print(f"  {s['desde']} -> {s['hasta']}  msg={s['mensajes']:<5} "
              f"{(s['modelo'] or ''):<28} {n[:44]}")


if __name__ == "__main__":
    main()
