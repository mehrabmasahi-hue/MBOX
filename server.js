const http=require('http');
const fs=require('fs');
const path=require('path');
const os=require('os');
const crypto=require('crypto');
const {WebSocketServer}=require('ws');

const PORT=1212;
const rooms=new Map();
const clients=new Map();
const pub=path.join(__dirname,'public');

const mime={
'.html':'text/html; charset=utf-8',
'.css':'text/css; charset=utf-8',
'.js':'application/javascript; charset=utf-8',
'.json':'application/json',
'.png':'image/png',
'.ico':'image/x-icon'
};

const server=http.createServer((req,res)=>{
 const url=new URL(req.url,'http://localhost');

 if(url.pathname==='/api/rooms'){
  res.writeHead(200,{'Content-Type':'application/json'});
  return res.end(JSON.stringify([...rooms.values()].map(r=>view(r))));
 }

 if(url.pathname==='/api/health'){
  res.writeHead(200,{'Content-Type':'application/json'});
  return res.end(JSON.stringify({ok:true,name:'M BOX'}));
 }

 let file=path.resolve(pub,'.'+decodeURIComponent(url.pathname==='/'?'/index.html':url.pathname));
 if(!file.startsWith(pub+path.sep)){
  res.writeHead(403);return res.end();
 }
 fs.readFile(file,(err,data)=>{
  if(err){res.writeHead(404);return res.end('Not found');}
  res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream'});
  res.end(data);
 });
});

function view(r){
 return {
  id:r.id,hostName:r.hostName,status:r.status,game:r.game,
  players:[...r.players.values()].map(p=>({
   id:p.id,name:p.name,approved:p.approved,connected:p.connected
  }))
 };
}

function send(id,data){
 const c=clients.get(id);
 if(c&&c.readyState===1)c.send(JSON.stringify(data));
}

function broadcast(r,data){
 send(r.hostId,data);
 for(const id of r.players.keys())send(id,data);
}

function update(r){
 broadcast(r,{type:'room',room:view(r)});
}

function clean(s){
 return String(s||'Player').replace(/[^a-zA-Z0-9_\u0600-\u06FF -]/g,'').slice(0,20);
}

function disconnect(ws){
 const c=clients.get(ws.id);
 if(!c)return;
 clients.delete(ws.id);
 if(!c.roomId)return;
 const r=rooms.get(c.roomId);
 if(!r)return;
 if(r.hostId===c.id){
  for(const id of r.players.keys()){
   const p=clients.get(id);
   if(p)p.roomId=null;
   send(id,{type:'notice',message:'مدیر اتاق خارج شد.'});
  }
  rooms.delete(r.id);
 }else{
  const p=r.players.get(c.id);
  if(p){p.connected=false;update(r);}
 }
}

const wss=new WebSocketServer({server,path:'/ws'});

wss.on('connection',ws=>{
 ws.id=crypto.randomBytes(8).toString('hex');
 clients.set(ws.id,ws);
 send(ws.id,{type:'welcome',id:ws.id});

 ws.on('message',raw=>{
  let m;
  try{m=JSON.parse(raw.toString())}catch{return;}
  const c=clients.get(ws.id);
  if(!c)return;

  if(m.type==='create'){
   const id=String(Math.floor(100000+Math.random()*900000));
   const r={
    id,hostId:c.id,hostName:clean(m.name),
    players:new Map(),status:'lobby',game:null
   };
   c.roomId=id;c.role='host';
   rooms.set(id,r);
   send(c.id,{type:'created',room:view(r)});
   update(r);
  }

  if(m.type==='join'){
   const r=rooms.get(String(m.roomId||''));
   if(!r)return send(c.id,{type:'error',message:'اتاق پیدا نشد.'});
   if(r.players.size>=4)return send(c.id,{type:'error',message:'ظرفیت پر است.'});
   if(c.roomId)return;
   const p={
    id:c.id,name:clean(m.name),approved:false,connected:true
   };
   r.players.set(c.id,p);
   c.roomId=r.id;c.role='player';
   send(c.id,{type:'joined',room:view(r)});
   send(r.hostId,{type:'request',player:{id:c.id,name:p.name}});
   update(r);
  }

  if(m.type==='approve'){
   const r=rooms.get(c.roomId);
   if(!r||r.hostId!==c.id)return;
   const p=r.players.get(m.playerId);
   if(!p)return;
   p.approved=!!m.approved;
   send(p.id,{type:'approval',approved:p.approved});
   update(r);
  }

  if(m.type==='loadGame'){
   const r=rooms.get(c.roomId);
   if(!r||r.hostId!==c.id)return;
   const g=m.game;
   if(!g||g.format!=='MBOX-123T')return;
   if(!['race','coop'].includes(g.engine))return;
   r.game={
    title:String(g.title||'بازی').slice(0,50),
    engine:g.engine,
    mode:g.mode,
    maxPlayers:Math.min(4,Math.max(1,Number(g.maxPlayers)||4)),
    target:Math.min(100,Math.max(5,Number(g.target)||20))
   };
   update(r);
  }

  if(m.type==='start'){
   const r=rooms.get(c.roomId);
   if(!r||r.hostId!==c.id||!r.game)return;
   if(![...r.players.values()].some(p=>p.approved&&p.connected))
    return send(c.id,{type:'error',message:'ابتدا یک بازیکن را تأیید کن.'});
   r.status='playing';
   broadcast(r,{type:'gameStart',game:r.game,players:view(r).players});
   update(r);
  }

  if(m.type==='input'){
   const r=rooms.get(c.roomId);
   if(!r||r.status!=='playing'||c.role!=='player')return;
   const p=r.players.get(c.id);
   if(!p||!p.approved)return;
   send(r.hostId,{
    type:'playerInput',playerId:c.id,
    action:String(m.action||'').slice(0,20)
   });
  }

  if(m.type==='gameEvent'){
   const r=rooms.get(c.roomId);
   if(r&&r.hostId===c.id&&r.status==='playing')
    broadcast(r,{type:'gameEvent',event:m.event});
  }

  if(m.type==='endGame'){
   const r=rooms.get(c.roomId);
   if(!r||r.hostId!==c.id)return;
   r.status='lobby';
   broadcast(r,{type:'gameEnded'});
   update(r);
  }

  if(m.type==='leave'){
   disconnect(ws);
   c.roomId=null;
  }
 });

 ws.on('close',()=>disconnect(ws));
});

server.listen(PORT,'0.0.0.0',()=>{
 console.log('\n================================');
 console.log('       M BOX STARTED');
 console.log('================================');
 console.log('http://localhost:'+PORT);
 for(const list of Object.values(os.networkInterfaces()))
  for(const n of list||[])
   if(n.family==='IPv4'&&!n.internal)
    console.log('http://'+n.address+':'+PORT);
 console.log('================================');
});
