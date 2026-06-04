#!/usr/bin/env python3
"""
Bikernieki Race Track Calendar Scraper & ICS Generator
Author: Antigravity AI
License: MIT
"""

import os
import re
import html
import urllib.request
import urllib.parse
import hashlib
import datetime
import time
import argparse
import threading
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, HTTPServer

# Constants
BASE_URL = "https://bksb.lv"
CALENDAR_PATH = "/index.php/2014-01-03-13-49-44/month.calendar"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
HEADERS = {'User-Agent': USER_AGENT}

# Timezone Definition for Europe/Riga (Latvia EET/EEST)
VTIMEZONE_BLOCK = """BEGIN:VTIMEZONE
TZID:Europe/Riga
BEGIN:STANDARD
DTSTART:19701025T040000
RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
TZOFFSETFROM:+0300
TZOFFSETTO:+0200
TZNAME:EET
END:STANDARD
BEGIN:DAYLIGHT
DTSTART:19700329T030000
RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
TZOFFSETFROM:+0200
TZOFFSETTO:+0300
TZNAME:EEST
END:DAYLIGHT
END:VTIMEZONE"""# Excluded locations that do not impact public access to the race track
EXCLUDED_LOCATIONS = {
    "bksb birojs",
    "bksb spidveja stadions",
    "bksb motormuzeja likums",
    "bksb liela stavvieta"
}

def normalize_text(text):
    """Normalizes string for comparison by lowercasing, stripping quotes, and removing Latvian diacritics."""
    if not text:
        return ""
    text = text.lower()
    # Strip common Latvian quotes and standard quotes
    text = text.replace('"', '').replace("'", "").replace('“', '').replace('”', '').replace('«', '').replace('»', '')
    # Map Latvian characters to standard latin
    replacements = {
        'ā': 'a', 'ē': 'e', 'ī': 'i', 'ū': 'u', 'ō': 'o',
        'ķ': 'k', 'ļ': 'l', 'ņ': 'n', 'ģ': 'g', 'š': 's', 'ž': 'z'
    }
    for k, v in replacements.items():
        text = text.replace(k, v)
    return " ".join(text.split())





class JEventsHTMLParser(HTMLParser):
    """Parses JEvents monthly calendar output for events."""
    def __init__(self):
        super().__init__()
        self.events = []
        self.in_title = False
        self.current_title = ""
        self.current_href = ""
        self.current_tooltip = ""

    def handle_starttag(self, tag, attrs):
        attrs_dict = dict(attrs)
        # Identify the outer container holding event tooltips (JEvents hasjevtip editlinktip)
        if tag == 'span' and 'editlinktip' in attrs_dict.get('class', ''):
            self.current_tooltip = attrs_dict.get('title', '')
        # Identify the title link
        if tag == 'a' and 'cal_titlelink' in attrs_dict.get('class', ''):
            self.in_title = True
            self.current_href = attrs_dict.get('href', '')
            self.current_title = ""

    def handle_endtag(self, tag):
        if tag == 'a' and self.in_title:
            self.in_title = False
            self.events.append({
                'title_text': self.current_title.strip(),
                'href': self.current_href,
                'tooltip': self.current_tooltip
            })
            self.current_href = ""
            self.current_tooltip = ""

    def handle_data(self, data):
        if self.in_title:
            self.current_title += data


def get_target_months(count=3):
    """Returns a list of (year, month) tuples starting from the current month."""
    today = datetime.date.today()
    year = today.year
    month = today.month
    
    targets = []
    for _ in range(count):
        targets.append((year, month))
        month += 1
        if month > 12:
            month = 1
            year += 1
    return targets


def parse_time_and_summary(title_text):
    """
    Parses event times and clean summary from the JEvents anchor text.
    Handles 'HH:MM-HH:MM Summary' or single time 'HH:MM Summary', fallback to all-day.
    """
    # Range match: "08:00-20:00 BaTCC autosacensības"
    range_match = re.match(r'^(\d{2}:\d{2})-(\d{2}:\d{2})\s+(.*)$', title_text)
    if range_match:
        return range_match.group(1), range_match.group(2), range_match.group(3)
        
    # Single time match: "18:30 Riteņbraukšanas treniņš"
    single_match = re.match(r'^(\d{2}:\d{2})\s+(.*)$', title_text)
    if single_match:
        t = single_match.group(1)
        # Default single time events to 1 hour duration
        try:
            h, m = map(int, t.split(':'))
            end_h = (h + 1) % 24
            end_t = f"{end_h:02d}:{m:02d}"
        except Exception:
            end_t = t
        return t, end_t, single_match.group(2)
        
    # Fallback all-day event
    return "00:00", "23:59", title_text


def clean_description_field(tooltip_html):
    """Extracts Category and Location from tooltip HTML content."""
    tooltip = html.unescape(tooltip_html)
    
    # Extract Category: 'Kategorija: Autošoseja, Svarigie <br>'
    category_match = re.search(r'Kategorija:\s*([^<]+)', tooltip)
    category = category_match.group(1).strip() if category_match else ""
    
    # Extract Location: 'Kur: BKSB lielā auto trase <br>'
    location_match = re.search(r'Kur:\s*([^<]+)', tooltip)
    location = location_match.group(1).strip() if location_match else ""
    
    # Clean up standard tags
    category = re.sub(r'<[^>]+>', '', category).strip()
    location = re.sub(r'<[^>]+>', '', location).strip()
    
    return category, location


def fetch_and_scrape_month(year, month):
    """Fetches and parses a single month's calendar page."""
    url = f"{BASE_URL}{CALENDAR_PATH}/{year}/{month:02d}/01/-"
    print(f"[*] Fetching calendar: {url}")
    
    req = urllib.request.Request(url, headers=HEADERS)
    content = None
    last_error = None

    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                content = resp.read().decode('utf-8')
            break
        except Exception as e:
            last_error = e
            print(f"[!] Attempt {attempt + 1}/2 failed for {year}/{month:02d}: {e}")
            if attempt < 1:
                time.sleep(2)

    if content is None:
        print(f"[!] Error loading calendar for {year}/{month:02d} after retries: {last_error}")
        return []

    parser = JEventsHTMLParser()
    parser.feed(content)
    
    parsed_events = []
    for ev in parser.events:
        href = ev['href']
        title_text = ev['title_text']
        tooltip_html = ev['tooltip']
        
        # Extract date from href (e.g. .../icalrepeat.detail/2026/05/01/9874/-/slug)
        date_match = re.search(r'icalrepeat\.detail/(\d{4})/(\d{2})/(\d{2})', href)
        if not date_match:
            continue
            
        eyear, emonth, eday = date_match.groups()
        date_str = f"{eyear}-{emonth}-{eday}"
        
        start_time, end_time, summary = parse_time_and_summary(title_text)
        category, location = clean_description_field(tooltip_html)
        
        # Filter out events at locations that do not impact public access
        if normalize_text(location) in EXCLUDED_LOCATIONS:
            print(f"[-] Excluding event due to location '{location}': {summary}")
            continue
            
        # Handle midnight crossing for overnight events
        end_date = date_str
        if end_time <= start_time:
            try:
                start_dt = datetime.datetime.strptime(f"{date_str} {start_time}", "%Y-%m-%d %H:%M")
                end_dt = datetime.datetime.strptime(f"{date_str} {end_time}", "%Y-%m-%d %H:%M")
                if end_dt <= start_dt:
                    end_date = (start_dt + datetime.timedelta(days=1)).strftime("%Y-%m-%d")
            except Exception:
                pass
        
        event_url = f"{BASE_URL}{href}" if href.startswith('/') else href
        
        raw_uid_data = f"{date_str}|{start_time}|{end_date}|{end_time}|{title_text}|{location}"
        uid = hashlib.md5(raw_uid_data.encode('utf-8')).hexdigest() + "@bikernieku-calendar"
        
        parsed_events.append({
            'start_date': date_str,
            'start_time': start_time,
            'end_date': end_date,
            'end_time': end_time,
            'summary': summary,
            'category': category,
            'location': location if location else "Bikernieku Trase",
            'url': event_url,
            'uid': uid
        })
        
    return parsed_events


def scrape_full_calendar(months_count=3):
    """Scrapes multiple lookahead months and returns deduplicated list of events."""
    targets = get_target_months(months_count)
    all_events = []
    
    for year, month in targets:
        monthly_events = fetch_and_scrape_month(year, month)
        all_events.extend(monthly_events)
        time.sleep(0.5) # Friendly rate limiting
        
    # Deduplicate events based on stable UID
    seen_uids = set()
    deduped = []
    for ev in all_events:
        if ev['uid'] not in seen_uids:
            seen_uids.add(ev['uid'])
            deduped.append(ev)
            
    # Sort events by start date and start time
    deduped.sort(key=lambda x: (x['start_date'], x['start_time']))
    return deduped


def escape_ics_text(text):
    """Escapes characters requiring backslashes in standard iCalendar fields."""
    if not text:
        return ""
    text = text.replace("\\", "\\\\")
    text = text.replace(",", "\\,")
    text = text.replace(";", "\\;")
    text = text.replace("\n", "\\n")
    return text


def fold_ics_line(line):
    """RFC 5545 UTF-8 byte folding at 75-octet limits."""
    encoded = line.encode('utf-8')
    if len(encoded) <= 75:
        return line
        
    res = bytearray()
    idx = 0
    current_line_len = 0
    while idx < len(encoded):
        b = encoded[idx]
        if b & 0x80 == 0:
            char_len = 1
        elif b & 0xE0 == 0xC0:
            char_len = 2
        elif b & 0xF0 == 0xE0:
            char_len = 3
        elif b & 0xF8 == 0xF0:
            char_len = 4
        else:
            char_len = 1
            
        if current_line_len + char_len > 74: # 74 octets max to allow CR LF space space
            res.extend(b'\r\n ')
            current_line_len = 1 # Counts the indentation space
            
        res.extend(encoded[idx:idx+char_len])
        current_line_len += char_len
        idx += char_len
        
    return res.decode('utf-8')


def build_ics_file(events):
    """Constructs a fully-compliant iCalendar format string from a list of events."""
    lines = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Bikernieku Calendar//EN",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
        "X-WR-CALNAME:Biķernieku Trases Kalendārs",
        "X-WR-TIMEZONE:Europe/Riga"
    ]
    
    # Add Latvia timezone definition block
    lines.extend(VTIMEZONE_BLOCK.split('\n'))
    
    dtstamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    
    for ev in events:
        start_date_clean = ev['start_date'].replace("-", "")
        end_date_clean = ev['end_date'].replace("-", "")
        start_clean = ev['start_time'].replace(":", "") + "00"
        end_clean = ev['end_time'].replace(":", "") + "00"
        
        lines.append("BEGIN:VEVENT")
        lines.append(f"UID:{ev['uid']}")
        lines.append(f"DTSTAMP:{dtstamp}")
        lines.append(f"DTSTART;TZID=Europe/Riga:{start_date_clean}T{start_clean}")
        lines.append(f"DTEND;TZID=Europe/Riga:{end_date_clean}T{end_clean}")
        
        summary = escape_ics_text(ev['summary'])
        location = escape_ics_text(ev['location'])
        
        lines.append(f"SUMMARY:{summary}")
        if location:
            lines.append(f"LOCATION:{location}")
            
        desc = []
        if ev['category']:
            desc.append(f"Kategorija: {ev['category']}")
        desc.append(f"Pasākuma saite: {ev['url']}")
        desc_text = "\n".join(desc)
        lines.append(f"DESCRIPTION:{escape_ics_text(desc_text)}")
        
        lines.append("END:VEVENT")
        
    lines.append("END:VCALENDAR")
    
    # Fold lines to 75 octet limits
    folded_lines = [fold_ics_line(line) for line in lines]
    return "\r\n".join(folded_lines) + "\r\n"


# Cache System for HTTP Server
class CalendarCache:
    def __init__(self, output_file, months_count):
        self.output_file = output_file
        self.months_count = months_count
        self.events = []
        self.ics_data = ""
        self.last_updated = None
        self.lock = threading.Lock()

    def load_from_disk(self):
        """Loads cached ICS file if it exists, parsing events out of it roughly for display."""
        if os.path.exists(self.output_file):
            print(f"[*] Found existing calendar file: {self.output_file}")
            try:
                with open(self.output_file, 'r', encoding='utf-8') as f:
                    self.ics_data = f.read()
                self.last_updated = datetime.datetime.fromtimestamp(os.path.getmtime(self.output_file))
                # Populate basic events from the ICS file (simplistic parser for dashboard statistics)
                self.events = self._quick_parse_ics_events(self.ics_data)
                print(f"[*] Loaded {len(self.events)} events from local disk cache.")
            except Exception as e:
                print(f"[!] Error loading {self.output_file}: {e}")

    def refresh(self):
        """Forces scraping of track events, saves it to file and memory cache."""
        with self.lock:
            print("[*] Refreshing calendar cache...")
            try:
                self.events = scrape_full_calendar(self.months_count)

                # Protect against total scrape failure overwriting good data
                if len(self.events) == 0 and os.path.exists(self.output_file) and os.path.getsize(self.output_file) > 500:
                    print("[!] Scrape returned 0 events. Preserving existing calendar file.")
                    return False

                self.ics_data = build_ics_file(self.events)

                # Write to output file
                with open(self.output_file, 'w', encoding='utf-8') as f:
                    f.write(self.ics_data)

                self.last_updated = datetime.datetime.now()
                print(f"[+] Successfully refreshed. Extracted {len(self.events)} events.")
                return True
            except Exception as e:
                print(f"[!] Scraper refresh failed: {e}")
                return False

    def _quick_parse_ics_events(self, ics_str):
        """Simplistic ICS parser to construct event summaries for status page."""
        events = []
        current = {}
        for line in ics_str.splitlines():
            line = line.strip()
            if line == "BEGIN:VEVENT":
                current = {'location': '', 'category': '', 'summary': ''}
            elif line.startswith("DTSTART"):
                m = re.search(r':(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})', line)
                if m:
                    current['start_date'] = f"{m.group(1)}-{m.group(2)}-{m.group(3)}"
                    current['start_time'] = f"{m.group(4)}:{m.group(5)}"
            elif line.startswith("DTEND"):
                m = re.search(r':\d{4}\d{2}\d{2}T(\d{2})(\d{2})', line)
                if m:
                    current['end_time'] = f"{m.group(1)}:{m.group(2)}"
            elif line.startswith("SUMMARY:"):
                # Clean simple escapes
                val = line[8:].replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
                current['summary'] = val
            elif line.startswith("LOCATION:"):
                val = line[9:].replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
                current['location'] = val
            elif line.startswith("DESCRIPTION:"):
                val = line[12:]
                m_cat = re.search(r'Kategorija: ([^\\]+)', val)
                if m_cat:
                    current['category'] = m_cat.group(1).replace("\\,", ",").strip()
            elif line == "END:VEVENT":
                if 'start_date' in current:
                    events.append(current)
        events.sort(key=lambda x: (x.get('start_date', ''), x.get('start_time', '')))
        return events


# Global Cache instance
cache_instance = None

class CalendarHTTPRequestHandler(BaseHTTPRequestHandler):
    """Simple web status dashboard and ICS feed server."""
    def log_message(self, format, *args):
        # Prevent standard requests spam in logs unless error
        pass

    def do_GET(self):
        global cache_instance
        
        # Serves raw iCalendar subscription feed
        if self.path == "/calendar.ics":
            # Auto update if cache older than 12 hours
            time_since_update = datetime.timedelta(hours=24)
            if cache_instance.last_updated:
                time_since_update = datetime.datetime.now() - cache_instance.last_updated
                
            if cache_instance.last_updated is None or time_since_update.total_seconds() > 43200: # 12 hours
                print("[*] Cache expired. Scraping in background...")
                # Run refresh in a background thread to prevent client timeouts
                threading.Thread(target=cache_instance.refresh).start()
                
            self.send_response(200)
            self.send_header("Content-Type", "text/calendar; charset=utf-8")
            self.send_header("Content-Disposition", "attachment; filename=bikernieki.ics")
            self.send_header("Access-Control-Allow-Origin", "*")
            
            # Send cache data (even if slightly stale, background thread is fetching new copy)
            response_bytes = cache_instance.ics_data.encode('utf-8')
            self.send_header("Content-Length", str(len(response_bytes)))
            self.end_headers()
            self.wfile.write(response_bytes)
            
        # Serves beautiful dashboard UI page
        elif self.path == "/" or self.path == "/index.html":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(self.render_dashboard().encode('utf-8'))
            
        # Forces re-scrape
        elif self.path == "/refresh":
            print("[*] Force refresh requested via Web UI")
            success = cache_instance.refresh()
            # Redirect back to index
            self.send_response(303)
            self.send_header("Location", "/")
            self.end_headers()
            
        else:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"404 Not Found")

    def render_dashboard(self):
        """Generates premium dark racing-themed dashboard HTML."""
        status_time = cache_instance.last_updated.strftime("%Y-%m-%d %H:%M:%S") if cache_instance.last_updated else "Never"
        event_count = len(cache_instance.events)
        
        # Build event table rows
        rows = []
        if not cache_instance.events:
            rows.append("<tr><td colspan='5' style='text-align:center; padding: 2rem; color: #888;'>No events parsed. Refresh to fetch events.</td></tr>")
        else:
            for idx, ev in enumerate(cache_instance.events):
                summary = html.escape(ev.get('summary', ''))
                date_str = html.escape(ev.get('start_date', ''))
                time_str = f"{ev.get('start_time', '')} - {ev.get('end_time', '')}"
                loc = html.escape(ev.get('location', ''))
                cat = html.escape(ev.get('category', ''))
                
                rows.append(f"""
                <tr>
                    <td class="text-bold">{idx+1}</td>
                    <td class="text-yellow">{date_str}</td>
                    <td class="time-badge">{time_str}</td>
                    <td>{summary}</td>
                    <td class="location-col">{loc}</td>
                </tr>
                """)
        
        table_body = "\n".join(rows)
        
        # HTML document with CSS inside
        return f"""<!DOCTYPE html>
<html lang="lv">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Biķernieku Trases Kalendārs Dashboard</title>
    <link href="https://fonts.googleapis.com/css2?family=Montserrat:wght@400;600;800&family=Outfit:wght@300;400;600&display=swap" rel="stylesheet">
    <style>
        :root {{
            --bg-color: #0e0f11;
            --card-color: #16181c;
            --yellow: #f5c400;
            --yellow-hover: #ffd93d;
            --text-main: #f3f4f6;
            --text-muted: #9ca3af;
            --border-color: #242730;
            --accent-green: #10b981;
        }}
        
        * {{
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }}
        
        body {{
            background-color: var(--bg-color);
            color: var(--text-main);
            font-family: 'Outfit', sans-serif;
            line-height: 1.5;
            padding: 2rem 1rem;
        }}
        
        .container {{
            max-width: 1100px;
            margin: 0 auto;
        }}
        
        header {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 2px solid var(--border-color);
            padding-bottom: 1.5rem;
            margin-bottom: 2rem;
        }}
        
        .title-group {{
            display: flex;
            align-items: center;
            gap: 1rem;
        }}
        
        .logo-placeholder {{
            background: linear-gradient(135deg, var(--yellow), #c09000);
            width: 48px;
            height: 48px;
            border-radius: 8px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 800;
            font-size: 1.5rem;
            color: #000;
            font-family: 'Montserrat', sans-serif;
            letter-spacing: -1px;
            box-shadow: 0 4px 15px rgba(245, 196, 0, 0.3);
        }}
        
        h1 {{
            font-family: 'Montserrat', sans-serif;
            font-weight: 800;
            font-size: 1.8rem;
            letter-spacing: -0.5px;
        }}
        
        .sub-header {{
            color: var(--text-muted);
            font-size: 0.95rem;
        }}
        
        .btn {{
            background-color: var(--yellow);
            color: #000;
            border: none;
            padding: 0.75rem 1.5rem;
            border-radius: 8px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s ease-in-out;
            font-family: 'Outfit', sans-serif;
            display: inline-flex;
            align-items: center;
            gap: 0.5rem;
            text-decoration: none;
            box-shadow: 0 4px 12px rgba(245, 196, 0, 0.2);
        }}
        
        .btn:hover {{
            background-color: var(--yellow-hover);
            transform: translateY(-2px);
            box-shadow: 0 6px 16px rgba(245, 196, 0, 0.3);
        }}
        
        .btn:active {{
            transform: translateY(0);
        }}
        
        .btn-secondary {{
            background-color: transparent;
            color: var(--text-main);
            border: 1px solid var(--border-color);
            box-shadow: none;
        }}
        
        .btn-secondary:hover {{
            background-color: var(--border-color);
            color: #fff;
            transform: translateY(-2px);
            box-shadow: none;
        }}
        
        .grid {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
            gap: 1.5rem;
            margin-bottom: 2.5rem;
        }}
        
        .card {{
            background-color: var(--card-color);
            border: 1px solid var(--border-color);
            border-radius: 12px;
            padding: 1.5rem;
            display: flex;
            flex-direction: column;
            justify-content: space-between;
            position: relative;
            overflow: hidden;
            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
        }}
        
        .card::before {{
            content: '';
            position: absolute;
            top: 0;
            left: 0;
            width: 4px;
            height: 100%;
            background-color: var(--border-color);
        }}
        
        .card.active::before {{
            background-color: var(--accent-green);
        }}
        
        .card.accent::before {{
            background-color: var(--yellow);
        }}
        
        .card-label {{
            color: var(--text-muted);
            font-size: 0.85rem;
            text-transform: uppercase;
            letter-spacing: 1px;
            margin-bottom: 0.5rem;
            font-weight: 600;
        }}
        
        .card-value {{
            font-size: 1.6rem;
            font-weight: 600;
            color: #fff;
            font-family: 'Montserrat', sans-serif;
        }}
        
        .card-subtext {{
            margin-top: 0.5rem;
            font-size: 0.85rem;
            color: var(--text-muted);
            word-break: break-all;
        }}
        
        .card-subtext a {{
            color: var(--yellow);
            text-decoration: none;
        }}
        
        .card-subtext a:hover {{
            text-decoration: underline;
        }}
        
        .table-section {{
            background-color: var(--card-color);
            border: 1px solid var(--border-color);
            border-radius: 12px;
            padding: 1.5rem;
            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
        }}
        
        .table-header {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 1.2rem;
            border-bottom: 1px solid var(--border-color);
            padding-bottom: 0.75rem;
        }}
        
        .table-title {{
            font-family: 'Montserrat', sans-serif;
            font-weight: 600;
            font-size: 1.2rem;
        }}
        
        .table-wrapper {{
            overflow-x: auto;
        }}
        
        table {{
            width: 100%;
            border-collapse: collapse;
            text-align: left;
        }}
        
        th, td {{
            padding: 0.85rem 1rem;
            border-bottom: 1px solid var(--border-color);
        }}
        
        th {{
            color: var(--text-muted);
            font-weight: 600;
            font-size: 0.85rem;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }}
        
        tr:hover td {{
            background-color: rgba(255, 255, 255, 0.02);
        }}
        
        .text-bold {{
            font-weight: 600;
        }}
        
        .text-yellow {{
            color: var(--yellow);
            font-weight: 600;
        }}
        
        .time-badge {{
            background-color: rgba(245, 196, 0, 0.1);
            color: var(--yellow);
            padding: 0.25rem 0.6rem;
            border-radius: 6px;
            font-size: 0.85rem;
            font-weight: 600;
            display: inline-block;
            border: 1px solid rgba(245, 196, 0, 0.2);
            white-space: nowrap;
        }}
        
        .location-col {{
            color: var(--text-muted);
            font-size: 0.9rem;
        }}
        
        @media (max-width: 768px) {{
            header {{
                flex-direction: column;
                align-items: flex-start;
                gap: 1rem;
            }}
            .btn {{
                width: 100%;
                justify-content: center;
            }}
        }}
    </style>
</head>
<body>
    <div class="container">
        <header>
            <div class="title-group">
                <div class="logo-placeholder">B</div>
                <div>
                    <h1>Biķernieku Trase</h1>
                    <div class="sub-header">Neoficiālais iCalendar Abonēšanas Kalendārs</div>
                </div>
            </div>
            <div>
                <a href="/refresh" class="btn">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/></svg>
                    Atjaunināt datus
                </a>
            </div>
        </header>
        
        <div class="grid">
            <div class="card active">
                <div>
                    <div class="card-label">Statuss</div>
                    <div class="card-value" style="color: var(--accent-green);">Aktīvs</div>
                </div>
                <div class="card-subtext">Lokālais kalendāra serveris strādā.</div>
            </div>
            
            <div class="card accent">
                <div>
                    <div class="card-label">Abonementa URL</div>
                    <div class="card-value">iCal barotne</div>
                </div>
                <div class="card-subtext">
                    Abonēt kalendāru:<br>
                    <a href="/calendar.ics" target="_blank" id="feed-url">/calendar.ics</a>
                </div>
            </div>
            
            <div class="card">
                <div>
                    <div class="card-label">Pasākumi kalendārā</div>
                    <div class="card-value">{event_count}</div>
                </div>
                <div class="card-subtext">Pēdējo reizi lasīts: <strong>{status_time}</strong></div>
            </div>
        </div>
        
        <div class="table-section">
            <div class="table-header">
                <div class="table-title">Sinhronizētie pasākumi ({event_count})</div>
                <a href="/calendar.ics" class="btn btn-secondary" download>
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>
                    Lejuplādēt .ICS
                </a>
            </div>
            <div class="table-wrapper">
                <table>
                    <thead>
                        <tr>
                            <th style="width: 5%">#</th>
                            <th style="width: 15%">Datums</th>
                            <th style="width: 15%">Laiks</th>
                            <th style="width: 45%">Pasākums</th>
                            <th style="width: 20%">Vieta</th>
                        </tr>
                    </thead>
                    <tbody>
                        {table_body}
                    </tbody>
                </table>
            </div>
        </div>
    </div>
    
    <script>
        // Adjust the absolute link for easier copying/clicking
        document.getElementById('feed-url').href = window.location.origin + '/calendar.ics';
        document.getElementById('feed-url').innerText = window.location.origin + '/calendar.ics';
    </script>
</body>
</html>
"""


def run_unit_tests():
    """Runs verification parser assertions on mock html snippet and checks filters/buffers."""
    print("[*] Running parser unit tests...")
    
    mock_html = """
    <div class="jevdaydata"></div>
    <div class="jeveventrow slots1">
        <span class="editlinktip hasjevtip" title="&lt;div class=&quot;jevtt_title&quot; style = &quot;color:#000000;background-color:#00a4e6;&quot;&gt;Test Sacensības&lt;/div&gt;  Kategorija: Autošoseja &lt;br&gt;  Laiks: &lt;a class=&quot;cal_titlelink&quot; href=&quot;/index.php/icalrepeat.detail/2026/05/01/9999/-/test-sacensibas&quot;&gt;08:00-20:00 &lt;/a&gt; &lt;br&gt;  Kur: Lielā trase">
            <a class="cal_titlelink" href="/index.php/icalrepeat.detail/2026/05/01/9999/-/test-sacensibas">08:00-20:00 Test Sacensības</a>
        </span>
    </div>
    """
    
    parser = JEventsHTMLParser()
    parser.feed(mock_html)
    
    if len(parser.events) != 1:
        print("[!] Unit test FAILED: Parser did not extract the event.")
        return False
        
    ev = parser.events[0]
    href = ev['href']
    title_text = ev['title_text']
    tooltip_html = ev['tooltip']
    
    date_match = re.search(r'icalrepeat\.detail/(\d{4})/(\d{2})/(\d{2})', href)
    if not date_match:
        print("[!] Unit test FAILED: Could not match date in URL.")
        return False
        
    eyear, emonth, eday = date_match.groups()
    date_str = f"{eyear}-{emonth}-{eday}"
    
    start_time, end_time, summary = parse_time_and_summary(title_text)
    category, location = clean_description_field(tooltip_html)
    
    assert date_str == "2026-05-01", f"Expected '2026-05-01', got '{date_str}'"
    assert start_time == "08:00", f"Expected '08:00', got '{start_time}'"
    assert end_time == "20:00", f"Expected '20:00', got '{end_time}'"
    assert summary == "Test Sacensības", f"Expected 'Test Sacensības', got '{summary}'"
    assert category == "Autošoseja", f"Expected 'Autošoseja', got '{category}'"
    assert location == "Lielā trase", f"Expected 'Lielā trase', got '{location}'"
    
    # Test Location Normalization
    assert normalize_text('BKSB "Motormuzeja līkums"') == 'bksb motormuzeja likums'
    assert normalize_text('BKSB spīdveja stadions') == 'bksb spidveja stadions'
    assert normalize_text('BKSB BIROJS') == 'bksb birojs'
    assert normalize_text('BKSB lielā stāvvieta') == 'bksb liela stavvieta'
    assert 'bksb liela stavvieta' in EXCLUDED_LOCATIONS
    
    # Test RFC 5545 Line folding
    long_line = "SUMMARY:" + "A" * 100
    folded = fold_ics_line(long_line)
    lines = folded.split('\r\n')
    assert len(lines) == 2, f"Expected 2 lines for folded output, got {len(lines)}"
    assert lines[1].startswith(' '), "Folded line must start with a space"
    
    # Test stable UID generation
    raw_uid_data1 = "2026-05-01|08:00|2026-05-01|20:00|08:00-20:00 Test Sacensības|Lielā trase"
    uid1 = hashlib.md5(raw_uid_data1.encode('utf-8')).hexdigest() + "@bikernieku-calendar"
    
    raw_uid_data2 = "2026-05-01|08:00|2026-05-01|20:00|08:00-20:00 Test Sacensības|Lielā trase"
    uid2 = hashlib.md5(raw_uid_data2.encode('utf-8')).hexdigest() + "@bikernieku-calendar"
    assert uid1 == uid2, "UID must be stable"
    
    print("[+] All parser unit tests passed successfully!")
    return True


def main():
    parser = argparse.ArgumentParser(description="Bikernieki Race Track Calendar Parser and ICS Generator")
    parser.add_argument("-o", "--output", default="bikernieki.ics", help="Output path for static ICS file (default: bikernieki.ics)")
    parser.add_argument("-m", "--months", type=int, default=3, help="Number of months to scrape including current (default: 3)")
    parser.add_argument("-s", "--serve", action="store_true", help="Start local HTTP server dashboard and iCal subscription feed")
    parser.add_argument("-p", "--port", type=int, default=8080, help="Web server port (default: 8080)")
    parser.add_argument("-t", "--test", action="store_true", help="Run self-testing harness and parser validations")
    
    args = parser.parse_args()
    
    # Execute unit tests
    if args.test:
        success = run_unit_tests()
        exit(0 if success else 1)
        
    global cache_instance
    cache_instance = CalendarCache(args.output, args.months)
    
    # Pre-load cache from disk if file exists
    cache_instance.load_from_disk()
    
    if args.serve:
        # Start server mode
        # Make sure cache has data
        if not cache_instance.ics_data:
            print("[*] No calendar file found on disk. Performing initial scrape...")
            cache_instance.refresh()
            
        server_address = ('', args.port)
        httpd = HTTPServer(server_address, CalendarHTTPRequestHandler)
        print(f"[+] Web Server running at: http://localhost:{args.port}/")
        print(f"[+] Subscribe to your calendar at: http://localhost:{args.port}/calendar.ics")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n[*] Shutting down server...")
            httpd.server_close()
            
    else:
        # CLI / Cron mode: scrape and write to output file
        print(f"[*] Starting CLI scrape: lookahead = {args.months} months, saving to {args.output}...")
        success = cache_instance.refresh()
        if success:
            print(f"[+] Completed! Calendar written to {args.output}")
            exit(0)
        else:
            print("[!] Scrape execution failed.")
            exit(1)

if __name__ == "__main__":
    main()
