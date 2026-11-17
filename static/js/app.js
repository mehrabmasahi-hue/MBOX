const $=id=>document.getElementById(id);
let sid=null,currentRoom=null,adminToken=null,selectedGame=null,role=null,playerId=null,managerControllerUrl=null,polling=true;
const screens=['home','displaySetup','displayPanel','controllerSetup','controllerPanel'];
function show(id){screens.forEach(s=>$(s).classList.toggle('hidden',s!==id))}
function msg(el,t){el.textContent=t;el.classList.remove('hidden')}
async function api(action,data={}){
 const r=await fetch('/api/'+action,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sid,...data})});
 const d=await r.json(); if(!r.ok) throw Error(d.error||'خطا در ارتباط با سرور'); return d;
}
function status(t){$('net').textContent=t}
function esc(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function renderRoom(r){$('playerCount').textContent=`${r.players.length}/4`;$('players').innerHTML=r.players.map(p=>`<div class="player"><span>${p.number===1?'👑 ':'🎮 '}${esc(p.name)}</span><em>${p.connected?'متصل':'دسته هنوز وصل نشده'} · بازیکن ${p.number}</em></div>`).join('')}
async function loadGames(){try{const games=await (await fetch('/api/games')).json(),box=$('games');box.innerHTML='';games.forEach(g=>{const el=document.createElement('div');el.className='game-card';el.innerHTML=`<b>${esc(g.name)}</b><small>${g.min_players} تا ${g.max_players} بازیکن</small>`;el.onclick=()=>{selectedGame=g.id;document.querySelectorAll('.game-card').forEach(x=>x.classList.remove('selected'));el.classList.add('selected');$('startGame').disabled=false};box.appendChild(el)});if(!games.length)box.innerHTML='<small class="muted">هنوز بازی نصب نشده است. یک فایل 123T بارگذاری کنید.</small>'}catch(e){alert(e.message)}}
async function handle(ev,d){
 if(ev==='room_created'){currentRoom=d.room.code;adminToken=d.admin_token;managerControllerUrl=d.controller_url;$('roomCode').textContent=currentRoom;$('roomHeading').textContent=d.room.title;role='display';show('displayPanel');renderRoom(d.room);loadGames()}
 if(ev==='room_update')renderRoom(d);
 if(ev==='join_request'){const p=d.player,box=$('requests');if(box.querySelector('.muted'))box.innerHTML='';const el=document.createElement('div');el.className='player';el.id='req-'+p.id;el.innerHTML=`<span>🎮 ${esc(p.name)} · بازیکن ${p.number}</span><span class="request-actions"><button data-yes="${p.id}">تأیید</button><button class="reject" data-no="${p.id}">رد</button></span>`;box.appendChild(el)}
 if(ev==='join_result'){if(!d.ok){msg($('joinStatus'),d.error||'اتصال ناموفق بود.');return}if(d.pending){msg($('joinStatus'),d.message);return}role='controller';playerId=d.player_id;$('playerHeading').textContent=d.role==='admin_controller'?'دسته مدیر':'دسته بازی';$('playerNumber').textContent='بازیکن '+d.number;show('controllerPanel');$('waiting').classList.remove('hidden')}
 if(ev==='game_started'){if(role==='display'){$('displayStage').classList.remove('hidden');$('gameFrame').src=d.url}if(role==='controller'){$('waiting').classList.add('hidden');$('pad').classList.remove('hidden')}}
 if(ev==='game_stopped'){if(role==='display'){$('displayStage').classList.add('hidden');$('gameFrame').src='about:blank'}if(role==='controller'){$('waiting').classList.remove('hidden');$('pad').classList.add('hidden')}}
 if(ev==='game_input'){const f=$('gameFrame');if(role==='display'&&f&&f.contentWindow)f.contentWindow.postMessage({type:'MBOX_INPUT',...d},'*')}
 if(ev==='room_closed')alert(d.message);
}
async function poll(){while(polling){try{if(sid){const d=await (await fetch('/api/poll?sid='+encodeURIComponent(sid),{cache:'no-store'})).json();status('متصل به سرور محلی');for(const item of d.events||[])await handle(item.event,item.data)}}catch(e){status('ارتباط قطع شد؛ تلاش مجدد...')}await new Promise(r=>setTimeout(r,250))}}
async function init(){try{const d=await (await fetch('/api/connect',{method:'POST'})).json();sid=d.sid;status('متصل به سرور محلی');poll();const p=new URLSearchParams(location.search);if(p.get('mode')==='controller'){show('controllerSetup');$('joinCode').value=p.get('room')||'';setTimeout(joinController,350)}}catch(e){status('خطا در اتصال به سرور')}}
$('displayChoice').onclick=()=>show('displaySetup');$('controllerChoice').onclick=()=>show('controllerSetup');document.querySelectorAll('[data-back]').forEach(b=>b.onclick=()=>show('home'));
$('createRoom').onclick=async()=>{try{const d=await api('create_room',{name:$('managerName').value,title:$('roomTitle').value});await handle('room_created',d)}catch(e){alert(e.message)}};
$('requests').addEventListener('click',async e=>{const yes=e.target.dataset.yes,no=e.target.dataset.no;try{if(yes){await api('approve_player',{player_id:yes});$('requests').querySelector('#req-'+yes)?.remove()}if(no){await api('reject_player',{player_id:no});$('requests').querySelector('#req-'+no)?.remove()}if(!$('requests').children.length)$('requests').innerHTML='<small class="muted">درخواستی وجود ندارد.</small>'}catch(err){alert(err.message)}});
$('uploadFile').onchange=async e=>{const f=e.target.files[0];if(!f)return;const fd=new FormData();fd.append('file',f);try{const r=await fetch('/api/upload',{method:'POST',body:fd}),d=await r.json();if(!r.ok)throw Error(d.error||'خطا');alert('بازی نصب شد: '+d.game.name);await loadGames()}catch(err){alert('خطا در نصب بازی: '+err.message)}e.target.value=''};
$('startGame').onclick=async()=>{if(!selectedGame)return;try{await api('start_game',{game_id:selectedGame})}catch(e){alert(e.message)}};
$('stopGame').onclick=$('exitStage').onclick=async()=>{try{await api('stop_game')}catch(e){alert(e.message)}};
$('openManagerPad').onclick=()=>{if(managerControllerUrl)window.open(location.origin+managerControllerUrl,'mbox-manager-controller')};
$('joinRoom').onclick=joinController;
async function joinController(){try{const p=new URLSearchParams(location.search);await api('join_controller',{room:$('joinCode').value.trim().toUpperCase()||p.get('room')||'',token:p.get('token')||'',name:$('playerName').value})}catch(e){msg($('joinStatus'),e.message)}}
document.querySelectorAll('[data-key]').forEach(btn=>{const send=action=>api('game_input',{input:{button:btn.dataset.key,action,at:Date.now()}}).catch(()=>{});btn.addEventListener('pointerdown',e=>{e.preventDefault();btn.setPointerCapture?.(e.pointerId);send('down')});['pointerup','pointercancel','lostpointercapture'].forEach(ev=>btn.addEventListener(ev,e=>{e.preventDefault();send('up')}))});
init();
