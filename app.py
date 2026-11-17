#!/usr/bin/env python3
"""M BOX offline LAN server. Python standard library only."""
import json, os, re, secrets, shutil, socket, threading, time, uuid, zipfile
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, unquote

BASE=Path(__file__).resolve().parent
GAMES=BASE/'games'; UPLOADS=BASE/'uploads'; DATA=BASE/'data'
for p in (GAMES,UPLOADS,DATA): p.mkdir(exist_ok=True)
HOST='0.0.0.0'; PORT=int(os.environ.get('MBOX_PORT','1212'))
LOCK=threading.RLock(); ROOMS={}; CLIENTS={}; SID_INDEX={}

def safe_name(s):
    s=re.sub(r'[^a-zA-Z0-9_-]','-',str(s or '').strip())[:32]
    return s or 'game'

def push(sid,event,data):
    c=CLIENTS.get(sid)
    if c is not None:
        c['events'].append({'event':event,'data':data})
        if len(c['events'])>150: del c['events'][:50]

def push_room(code,event,data,exclude=None):
    for sid,info in list(SID_INDEX.items()):
        if info[0]==code and sid!=exclude: push(sid,event,data)

def public_room(r):
    players=[{'id':p['id'],'name':p['name'],'number':p['number'],'connected':p.get('sid') is not None}
             for p in r['players'].values() if not p.get('pending')]
    return {'code':r['code'],'title':r['title'],'players':players,'max_players':4,'status':r['status'],'game':r.get('game')}

def room_update(code):
    r=ROOMS.get(code)
    if r: push_room(code,'room_update',public_room(r))

def list_games():
    out=[]
    for p in GAMES.iterdir():
        mf=p/'manifest.json'
        if p.is_dir() and mf.is_file():
            try:
                m=json.loads(mf.read_text('utf-8'))
                out.append({'id':p.name,'name':m.get('name',p.name),'description':m.get('description',''),
                            'min_players':int(m.get('min_players',1)),'max_players':int(m.get('max_players',4)),'mode':m.get('mode','multi')})
            except Exception: pass
    return out

def install_123t(fileobj, filename):
    if not filename.lower().endswith('.123t'): raise ValueError('فقط فایل .123t پذیرفته می‌شود.')
    temp=UPLOADS/(uuid.uuid4().hex+'.zip'); fileobj.save(temp)
    try:
        with zipfile.ZipFile(temp) as z:
            infos=z.infolist(); names=[i.filename for i in infos]
            if len(infos)>300 or sum(i.file_size for i in infos)>24*1024*1024: raise ValueError('حجم یا تعداد فایل‌ها بیش از حد مجاز است.')
            for i in infos:
                n=i.filename; pp=Path(n)
                if pp.is_absolute() or '..' in pp.parts or n.startswith(('\\','/')) or (i.external_attr>>16)&0o170000==0o120000:
                    raise ValueError('مسیر یا پیوند ناامن در بسته بازی وجود دارد.')
            mf=next((n for n in names if Path(n).name=='manifest.json'),None)
            if not mf: raise ValueError('manifest.json پیدا نشد.')
            m=json.loads(z.read(mf).decode('utf-8'))
            if not m.get('name') or not m.get('entry'): raise ValueError('نام بازی و entry در manifest لازم است.')
            prefix=mf.rsplit('/',1)[0]+'/' if '/' in mf else ''
            entry=prefix+str(m['entry'])
            if entry not in names and str(m['entry']) not in names: raise ValueError('فایل اصلی بازی پیدا نشد.')
            gid=safe_name(m.get('id') or m['name']); dest=GAMES/gid
            if dest.exists(): gid+='-'+uuid.uuid4().hex[:5]; dest=GAMES/gid
            dest.mkdir()
            for i in infos:
                if i.is_dir(): continue
                rel=i.filename[len(prefix):] if prefix and i.filename.startswith(prefix) else i.filename
                if not rel: continue
                target=(dest/rel).resolve()
                if dest.resolve() not in target.parents: raise ValueError('مسیر نامعتبر در بسته بازی')
                target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(z.read(i))
            (dest/'manifest.json').write_text(json.dumps(m,ensure_ascii=False,indent=2),'utf-8')
            return {'id':gid,'name':m['name']}
    finally: temp.unlink(missing_ok=True)

class Handler(BaseHTTPRequestHandler):
    server_version='MBOX/2.0'
    def log_message(self,*args): pass
    def send_bytes(self,code,body,ctype='application/json; charset=utf-8',headers=None):
        self.send_response(code); self.send_header('Content-Type',ctype); self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        for k,v in (headers or {}).items(): self.send_header(k,v)
        self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
    def json(self,code,obj): self.send_bytes(code,json.dumps(obj,ensure_ascii=False).encode(),headers={'Access-Control-Allow-Origin':'*'})
    def read_json(self):
        n=int(self.headers.get('Content-Length','0')); return json.loads(self.rfile.read(min(n,1024*1024)) or b'{}')
    def do_OPTIONS(self): self.send_bytes(204,b'',headers={'Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'GET,POST,OPTIONS','Access-Control-Allow-Headers':'Content-Type'})
    def do_GET(self):
        path=unquote(urlparse(self.path).path)
        if path=='/': return self.file(BASE/'templates'/'index.html','text/html; charset=utf-8')
        if path=='/api/games': return self.json(200,list_games())
        if path=='/api/rooms': return self.json(200,[public_room(r) for r in ROOMS.values() if r['status']!='closed'])
        if path=='/api/poll':
            q=urlparse(self.path).query; from urllib.parse import parse_qs
            sid=parse_qs(q).get('sid',[''])[0]
            with LOCK:
                c=CLIENTS.get(sid)
                if not c: return self.json(403,{'error':'نشست معتبر نیست.'})
                events=c['events']; c['events']=[]
            return self.json(200,{'events':events})
        if path.startswith('/static/'):
            rel=path[len('/static/'):]; target=(BASE/'static'/rel).resolve()
            if BASE/'static' not in target.parents: return self.json(404,{'error':'not found'})
            return self.file(target)
        if path.startswith('/game/'):
            parts=path.split('/',3)
            if len(parts)<4 or not re.fullmatch(r'[A-Za-z0-9_-]{1,40}',parts[2]): return self.json(404,{'error':'not found'})
            folder=(GAMES/parts[2]).resolve(); target=(folder/parts[3]).resolve()
            if folder not in target.parents or not target.is_file(): return self.json(404,{'error':'not found'})
            return self.file(target)
        return self.json(404,{'error':'not found'})
    def file(self,path,ctype=None):
        try:
            if not path.is_file(): return self.json(404,{'error':'فایل پیدا نشد.'})
            import mimetypes
            typ=ctype or mimetypes.guess_type(str(path))[0] or 'application/octet-stream'
            return self.send_bytes(200,path.read_bytes(),typ)
        except (OSError,ValueError): return self.json(404,{'error':'فایل پیدا نشد.'})
    def do_POST(self):
        path=urlparse(self.path).path
        if path=='/api/connect':
            sid=secrets.token_urlsafe(18)
            with LOCK: CLIENTS[sid]={'events':[],'last':time.time()}
            return self.json(200,{'sid':sid})
        if path=='/api/upload':
            # Multipart parser is intentionally simple and bounded.
            n=int(self.headers.get('Content-Length','0'))
            if n>32*1024*1024: return self.json(413,{'error':'حجم فایل بیش از حد مجاز است.'})
            raw=self.rfile.read(n); boundary=self.headers.get('Content-Type','').split('boundary=')[-1].strip('"')
            if not boundary: return self.json(400,{'error':'فرمت آپلود نامعتبر است.'})
            try:
                chunks=raw.split(b'--'+boundary.encode())
                part=next(x for x in chunks if b'filename=' in x)
                head,content=part.split(b'\r\n\r\n',1); content=content.rsplit(b'\r\n',1)[0]
                match=re.search(br'filename="([^"]+)"',head); filename=match.group(1).decode('utf-8','replace') if match else 'game.123t'
                import io
                class F:
                    def save(self,p): Path(p).write_bytes(content)
                game=install_123t(F(),filename); return self.json(200,{'ok':True,'game':game})
            except Exception as e: return self.json(400,{'error':str(e)})
        try: data=self.read_json()
        except Exception: return self.json(400,{'error':'درخواست نامعتبر است.'})
        action=path.removeprefix('/api/')
        with LOCK:
            sid=str(data.get('sid','')); client=CLIENTS.get(sid)
            if not client: return self.json(403,{'error':'نشست معتبر نیست؛ صفحه را تازه‌سازی کنید.'})
            client['last']=time.time()
            if action=='create_room':
                name=str(data.get('name','مدیر')).strip()[:24] or 'مدیر'; code=secrets.token_hex(3).upper(); token=secrets.token_urlsafe(18)
                r={'code':code,'title':str(data.get('title','اتاق بازی'))[:32],'admin_token':token,'display_sid':sid,'players':{'p1':{'id':'p1','name':name,'number':1,'sid':None}},'status':'lobby','game':None}
                ROOMS[code]=r; SID_INDEX[sid]=(code,'display',None)
                return self.json(200,{'room':public_room(r),'admin_token':token,'controller_url':f'/?mode=controller&room={code}&token={token}'})
            if action=='join_controller':
                code=str(data.get('room','')).upper(); token=str(data.get('token','')); name=str(data.get('name','بازیکن')).strip()[:24] or 'بازیکن'; r=ROOMS.get(code)
                if not r: return self.json(404,{'ok':False,'error':'اتاق پیدا نشد.'})
                if token and secrets.compare_digest(token,r['admin_token']):
                    p=r['players']['p1']; p['sid']=sid; p['name']=name or p['name']; SID_INDEX[sid]=(code,'controller','p1')
                    push(sid,'join_result',{'ok':True,'role':'admin_controller','player_id':'p1','number':1,'room':public_room(r)}); room_update(code); push_room(code,'player_input_ready',{'player_id':'p1'})
                    return self.json(200,{'ok':True})
                if len(r['players'])>=4: return self.json(400,{'ok':False,'error':'ظرفیت اتاق تکمیل است.'})
                pid='p'+str(max([p['number'] for p in r['players'].values()]+[0])+1)
                r['players'][pid]={'id':pid,'name':name,'number':int(pid[1:]),'sid':None,'pending':True}; SID_INDEX[sid]=(code,'pending',pid)
                push(r['display_sid'],'join_request',{'player':{'id':pid,'name':name,'number':int(pid[1:])}})
                push(sid,'join_result',{'ok':True,'pending':True,'message':'درخواست ورود ارسال شد؛ منتظر تأیید مدیر بمانید.'})
                return self.json(200,{'ok':True})
            info=SID_INDEX.get(sid)
            if not info: return self.json(403,{'error':'ابتدا به یک اتاق متصل شوید.'})
            code,role,pid=info; r=ROOMS.get(code)
            if not r: return self.json(404,{'error':'اتاق بسته شده است.'})
            if action in ('approve_player','reject_player'):
                if role!='display': return self.json(403,{'error':'فقط میزبان اجازه این کار را دارد.'})
                targetpid=str(data.get('player_id','')); p=r['players'].get(targetpid)
                if not p or not p.get('pending'): return self.json(404,{'error':'درخواست پیدا نشد.'})
                target=next((s for s,v in SID_INDEX.items() if v==(code,'pending',targetpid)),None)
                if action=='approve_player':
                    p.pop('pending',None); p['sid']=target
                    if target: SID_INDEX[target]=(code,'controller',targetpid); push(target,'join_result',{'ok':True,'role':'controller','player_id':targetpid,'number':p['number'],'room':public_room(r)})
                else:
                    if target: push(target,'join_result',{'ok':False,'error':'درخواست ورود شما رد شد.'}); SID_INDEX.pop(target,None)
                    r['players'].pop(targetpid,None)
                room_update(code); return self.json(200,{'ok':True})
            if action=='start_game':
                if role!='display': return self.json(403,{'error':'فقط میزبان می‌تواند بازی را شروع کند.'})
                gid=safe_name(data.get('game_id')); mf=GAMES/gid/'manifest.json'
                if not mf.is_file(): return self.json(404,{'error':'بازی پیدا نشد.'})
                m=json.loads(mf.read_text('utf-8')); count=len([p for p in r['players'].values() if not p.get('pending')])
                if count<int(m.get('min_players',1)) or count>int(m.get('max_players',4)): return self.json(400,{'error':'تعداد بازیکنان با این بازی سازگار نیست.'})
                entry=str(m.get('entry','index.html')); r['game']={'id':gid,'name':m.get('name'),'entry':entry}; r['status']='playing'
                push_room(code,'game_started',{'game':r['game'],'url':f'/game/{gid}/{entry}'}) ; room_update(code); return self.json(200,{'ok':True})
            if action=='stop_game':
                if role!='display': return self.json(403,{'error':'فقط میزبان می‌تواند بازی را متوقف کند.'})
                r['status']='lobby'; r['game']=None; push_room(code,'game_stopped',{}); room_update(code); return self.json(200,{'ok':True})
            if action=='game_input':
                if role!='controller' or r['status']!='playing': return self.json(403,{'error':'ورودی دسته مجاز نیست.'})
                push_room(code,'game_input',{'player_id':pid,'input':data.get('input')},exclude=sid); return self.json(200,{'ok':True})
        return self.json(404,{'error':'عملیات ناشناخته است.'})

def local_ips():
    ips=set()
    try:
        s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.connect(('8.8.8.8',80)); ips.add(s.getsockname()[0]); s.close()
    except Exception: pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(),None,socket.AF_INET): ips.add(info[4][0])
    except Exception: pass
    return sorted(x for x in ips if not x.startswith('127.'))

if __name__=='__main__':
    print('\n'+'='*42+'\n              M BOX OFFLINE\n'+'='*42)
    print(f'سرور محلی روی پورت {PORT} اجرا می‌شود.')
    for ip in local_ips(): print(f'  http://{ip}:{PORT}')
    print(f'  http://127.0.0.1:{PORT}')
    print('برای توقف: Ctrl+C\n')
    ThreadingHTTPServer((HOST,PORT),Handler).serve_forever()
