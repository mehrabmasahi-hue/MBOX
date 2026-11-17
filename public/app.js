let ws,me,room,game,host=false,players={},stars=[],score=0,frame;
const $=id=>document.getElementById(id);
function connect(){
 ws=new WebSocket('ws://'+location.host+'/ws');
 ws.onopen=()=>$('status').textContent='متصل';
 ws.onclose=()=>{$('status').textContent='قطع اتصال';setTimeout(connect,2000)};
 ws.onmessage=e=>handle(JSON.parse(e.data));
}
function send(x){if(ws&&ws.readyState===1)ws.send(JSON.stringify(x))}
function show(id){['home','host','join','game'].forEach(x=>$(x).hidden=x!==id)}
function home(){show('home');loadRooms()}
function createRoom(){host=true;send({type:'create',name:$('name').value})}
function showJoin(){host=false;show('join');loadRooms()}
function joinRoom(){send({type:'join',roomId:$('roomCode').value,name:$('name').value})}
function loadRooms(){
 fetch('/api/rooms').then(r=>r.json()).then(data=>{
  $('rooms').innerHTML='';
  data.forEach(r=>{
   const b=document.createElement('button');
   b.textContent=r.hostName+' - '+r.id;
   b.onclick=()=>{$('roomCode').value=r.id;showJoin();joinRoom()};
   $('rooms').append(b);
  });
 });
}
function handle(m){
 if(m.type==='welcome')me=m.id;
 if(m.type==='created'){room=m.room;$('code').textContent=room.id;show('host');renderPlayers()}
 if(m.type==='joined'){room=m.room;show('join');$('wait').textContent='در انتظار تأیید مدیر'}
 if(m.type==='request')renderPlayers();
 if(m.type==='room'){room=m.room;renderPlayers()}
 if(m.type==='approval')$('wait').textContent=m.approved?'تأیید شدی؛ منتظر شروع بازی':'درخواست رد شد';
 if(m.type==='gameStart'){game=m.game;players={};m.players.forEach((p,i)=>players[p.id]={name:p.name,x:100+i*120,y:250,color:['#a88aff','#32d9ce','#ffb45c','#ff6f91'][i]});if(!host)players[me]={name:$('name').value,x:100,y:250,color:'#a88aff'};show('game');$('gameTitle').textContent=game.title;$('end').hidden=!host;run()}
 if(m.type==='playerInput'&&host)input(m.playerId,m.action);
 if(m.type==='gameEvent'&&!host){if(m.event.players)players=m.event.players;if(m.event.stars)stars=m.event.stars;if(m.event.score!==undefined)score=m.event.score}
 if(m.type==='gameEnded'){cancelAnimationFrame(frame);home()}
 if(m.type==='error')alert(m.message);
 if(m.type==='notice')alert(m.message);
}
function renderPlayers(){
 if(!room)return;
 $('players').innerHTML='';
 room.players.forEach(p=>{
  const d=document.createElement('div');d.className='player';
  const t=document.createElement('span');t.textContent=p.name+' - '+(p.approved?'تأیید شده':'در انتظار');
  d.append(t);
  if(!p.approved){
   const b=document.createElement('button');b.textContent='تأیید';b.onclick=()=>send({type:'approve',playerId:p.id,approved:true});d.append(b);
   const x=document.createElement('button');x.textContent='رد';x.onclick=()=>send({type:'approve',playerId:p.id,approved:false});d.append(x);
  }
  $('players').append(d);
 });
 $('start').disabled=!(game&&room.players.some(p=>p.approved&&p.connected));
}
function selectGame(type){
 game={format:'MBOX-123T',version:1,title:type==='race'?'مسابقه سرعت':'جمع‌آوری ستاره',engine:type,mode:type==='race'?'competitive':'coop',maxPlayers:4,target:20};
 send({type:'loadGame',game});$('gameName').textContent=game.title;renderPlayers();
}
$('file').onchange=async e=>{
 const f=e.target.files[0];if(!f)return;
 try{
  const g=JSON.parse(await f.text());
  if(g.format!=='MBOX-123T'||!['race','coop'].includes(g.engine))throw Error('فرمت فایل معتبر نیست');
  game=g;send({type:'loadGame',game});$('gameName').textContent=game.title;renderPlayers();
 }catch(err){alert(err.message)}
};
function startGame(){send({type:'start'})}
function move(action){if(!host)send({type:'input',action})}
function input(id,a){
 const p=players[id];if(!p)return;
 if(a==='left')p.x=Math.max(30,p.x-22);
 if(a==='right')p.x=Math.min(870,p.x+22);
 if(a==='up')p.y=Math.max(35,p.y-22);
 if(a==='down')p.y=Math.min(485,p.y+22);
 if(a==='action'){
  if(game.engine==='race')p.x=Math.min(870,p.x+40);
  else{
   const s=stars.find(s=>!s.got&&Math.hypot(s.x-p.x,s.y-p.y)<55);
   if(s){s.got=true;score++}
  }
 }
 send({type:'gameEvent',event:{players,stars,score}});
}
function run(){
 cancelAnimationFrame(frame);
 stars=Array.from({length:game.target},()=>({x:50+Math.random()*800,y:65+Math.random()*390,got:false}));
 score=0;
 const c=$('canvas'),ctx=c.getContext('2d');
 function draw(){
  if($('game').hidden)return;
  ctx.clearRect(0,0,900,520);
  ctx.fillStyle='#101a32';ctx.fillRect(0,0,900,520);
  ctx.strokeStyle='#ffffff12';
  for(let x=0;x<900;x+=45){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,520);ctx.stroke()}
  if(game.engine==='race'){
   ctx.fillStyle='#263451';ctx.fillRect(25,45,850,430);
   ctx.strokeStyle='#ffffff';ctx.setLineDash([16,15]);ctx.beginPath();ctx.moveTo(450,45);ctx.lineTo(450,475);ctx.stroke();ctx.setLineDash([]);
   ctx.fillStyle='white';ctx.fillText('FINISH',800,30);
  }else{
   ctx.fillStyle='#ffe078';ctx.font='bold 23px Tahoma';ctx.fillText('ستاره‌ها: '+score+'/'+game.target,30,35);
   stars.forEach(s=>{if(!s.got){ctx.fillStyle='#ffd45f';ctx.beginPath();ctx.arc(s.x,s.y,10,0,7);ctx.fill()}})
  }
  Object.values(players).forEach(p=>{
   ctx.fillStyle=p.color;ctx.beginPath();ctx.roundRect(p.x-22,p.y-14,44,28,8);ctx.fill();
   ctx.fillStyle='white';ctx.font='15px Tahoma';ctx.fillText(p.name,p.x-25,p.y-23);
  });
  frame=requestAnimationFrame(draw);
 }
 draw();
}
function endGame(){send({type:'endGame'})}
connect();loadRooms();setInterval(loadRooms,5000);
