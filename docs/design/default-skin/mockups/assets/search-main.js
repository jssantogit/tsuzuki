(()=>{
'use strict';
// Real-work demonstration fixtures, not a Provider catalogue. Scores are illustrative.
const artworks=[
 {file:'attack-on-titan-v01.jpg',w:322,h:500,title:'Attack on Titan',format:'Manga',year:2009},
 {file:'death-note-v01.jpg',w:329,h:500,title:'Death Note',format:'Manga',year:2003},
 {file:'fullmetal-alchemist-v01.jpg',w:333,h:500,title:'Fullmetal Alchemist',format:'Manga',year:2001},
 {file:'one-piece-v01.jpg',w:324,h:475,title:'One Piece',format:'Manga',year:1997},
 {file:'witch-hat-atelier-v01.jpg',w:348,h:500,title:'Witch Hat Atelier',format:'Manga',year:2016},
 {file:'solo-leveling-v01.jpg',folder:'search',w:349,h:500,title:'Solo Leveling',format:'Manhwa',year:2018},
 {file:'look-back.jpg',folder:'search',w:320,h:460,title:'Look Back',format:'One-shot',year:2021},
 {file:'100-girlfriends-v01.jpg',folder:'search',w:320,h:452,title:'The 100 Girlfriends Who Really, Really, Really, Really, Really Love You',format:'Manga',year:2019}
];
const items=artworks.map((a,i)=>({...a,id:'fixture-work-'+i,ts:i===0?8.6:i===3?8.8:null,scores:i===0?[{brand:'MyAnimeList',file:'myanimelist.svg',value:'8.5'},{brand:'Kitsu',file:'kitsu.svg',value:'84%'},{brand:'MangaUpdates',file:'mangaupdates.svg',value:'8.4/10'}]:i===1?[{brand:'MyAnimeList',file:'myanimelist.svg',value:'8.7'},{brand:'Kitsu',file:'kitsu.svg',value:'86%'}]:[{brand:'MyAnimeList',file:'myanimelist.svg',value:'8.4'}]}));
// Fixture IDs never establish verified canonical identity or merge by title.
const formatOrder=['Manga','Manhwa','Manhua','Webtoon','One-shot','Novel','Doujinshi'];
const resultFixtures=items.filter(i=>i.title.toLowerCase().includes('o'));
const configs=[
 {label:'Busca · Discovery',mode:'discovery',query:'',recents:['One Piece','Death Note','Witch Hat Atelier'],primary:true},
 {label:'Busca · Discovery sem recentes',mode:'discovery',query:'',recents:[]},
 {label:'Busca · carregando',mode:'loading',query:'one piece',recents:[]},
 {label:'Busca · resultados por formato',mode:'results',query:'o',recents:['One Piece']},
 {label:'Busca · resultados / título longo',mode:'results',query:'Really',recents:[],longTitle:true},
 {label:'Busca · nenhum resultado',mode:'empty',query:'obra inexistente',recents:[]},
 {label:'Busca · erro',mode:'error',query:'one piece',recents:[]},
 {label:'Busca · nenhum Provider compatível',mode:'providers',query:'',recents:[]}
];
const esc=v=>String(v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const searchIcon='<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.5"/><path d="m16 16 4.3 4.3"/></svg>';
function ratings(item){const nativeLimit=item.ts==null?2:1;const visible=item.scores.slice(0,nativeLimit);const extra=item.scores.length-visible.length;return '<span class="catalog-ratings">'+(item.ts!=null?'<span class="rating-slot" aria-label="Tsuzuki Rating '+item.ts+'"><img src="assets/tracking/tsuzuki-rating.webp" width="256" height="256" alt="Tsuzuki Rating"><span>'+item.ts.toFixed(1)+'</span></span>':'')+visible.map(s=>'<span class="rating-slot" aria-label="'+esc(s.brand+' '+s.value)+'"><img src="assets/tracking/'+s.file+'" alt="'+esc(s.brand)+'"><span>'+esc(s.value)+'</span></span>').join('')+(extra?'<span class="rating-more" aria-label="Mais '+extra+' ratings nativos">+'+extra+'</span>':'')+'</span>'}
function card(item,result=false){return '<button type="button" class="catalog-card" data-work="'+esc(item.id)+'" aria-label="Abrir '+esc(item.title)+'"><span class="cover-frame"><img class="cover od-media" src="assets/'+(item.folder||'perfil')+'/'+item.file+'" width="'+item.w+'" height="'+item.h+'" alt="Capa de '+esc(item.title)+'"></span><span class="catalog-name">'+esc(item.title)+'</span><span class="catalog-metadata">'+ratings(item)+(result&&item.year?'<span class="catalog-year">'+item.year+'</span>':'')+'</span></button>'}
const source=document.querySelector('#library-board .phone-frame');
if(!source)return;
const board=document.createElement('section');board.id='search-board';board.setAttribute('aria-label','Busca · oito frames para revisão');board.innerHTML='<h1 class="library-heading">Busca · Default Skin · revisão visual</h1><div class="library-frames"></div>';
function init(fig,cfg){
 const phone=source.cloneNode(true);fig.append(phone);
 phone.querySelectorAll('.sheet-scrim,.filter-sheet,.library-live').forEach(n=>n.remove());
 const app=phone.querySelector('.library-app');const navigation=app.querySelector('.bottom-nav').cloneNode(true);app.replaceChildren();
 app.innerHTML='<header class="search-top"><h1 class="search-title">Busca</h1><form class="query-form" role="search" aria-label="Buscar no catálogo">'+searchIcon+'<input type="search" aria-label="Buscar mangá" placeholder="Buscar mangá" enterkeyhint="search" autocomplete="off"><button class="query-clear" type="button" aria-label="Limpar busca" hidden><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button></form></header><div class="search-content"></div>';
 navigation.querySelectorAll('button').forEach(b=>{b.removeAttribute('aria-current');if(b.dataset.navName==='Busca')b.setAttribute('aria-current','page')});phone.querySelector('.phone-screen').append(navigation);
 const feedback=document.createElement('div');feedback.className='search-feedback';feedback.setAttribute('role','status');feedback.hidden=true;phone.querySelector('.phone-screen').append(feedback);
 const input=app.querySelector('input'),content=app.querySelector('.search-content'),clear=app.querySelector('.query-clear');
 const state={mode:cfg.mode,query:cfg.query,recents:[...cfg.recents],visible:cfg.mode==='results'?(cfg.longTitle?[items[7]]:resultFixtures.map(i=>({...i,ts:null,scores:[]}))):[],pending:cfg.mode==='results'&&!cfg.longTitle,generation:0};input.value=state.query;let timers=[];
 function cancel(){timers.forEach(clearTimeout);timers=[];state.generation++}
 function say(text){feedback.textContent=text;feedback.hidden=false;clearTimeout(say.timer);say.timer=setTimeout(()=>feedback.hidden=true,4000)}
 function empty(title,description,action){return '<section class="search-empty"><h2>'+title+'</h2><p>'+description+'</p>'+(action?'<button type="button" class="primary-action" data-action="'+action.key+'">'+action.label+'</button>':'')+'</section>'}
 function draw(){clear.hidden=!input.value;
 if(state.mode==='discovery'){
  const recent=state.recents.length?'<section class="discovery-section"><div class="section-head"><h2>Buscas recentes</h2><button class="quiet-action" type="button" data-action="clear-recents">Limpar</button></div><div class="recent-rail od-rail">'+state.recents.map(q=>'<button class="recent-chip" type="button" data-query="'+esc(q)+'">'+esc(q)+'</button>').join('')+'</div></section>':'';
  const blocks=[['Em alta',[items[7],items[3],items[5],items[6]]],['Populares',[items[1],items[2],items[3]]],['Mais bem avaliados',[items[2],items[4],items[0]]],['Mais favoritados',[items[3],items[1]]],['Atualizados recentemente',[items[4],items[0]]]];
  content.innerHTML=recent+blocks.filter(([,entries])=>entries.length).map(([name,entries])=>'<section class="discovery-section"><div class="section-head"><h2>'+name+'</h2></div><div class="catalog-rail od-rail">'+entries.map(i=>card(i)).join('')+'</div></section>').join('');
 }else if(state.mode==='loading')content.innerHTML='<section class="wait-state" role="status"><span class="wait-track" aria-hidden="true"><span></span></span><span>Buscando obras…</span></section>';
 else if(state.mode==='results'){drawResults();}
 else if(state.mode==='empty')content.innerHTML=empty('Nenhum resultado encontrado','Tente outra busca.');
 else if(state.mode==='error')content.innerHTML=empty('Não foi possível concluir a busca','Tente novamente em instantes.',{key:'retry',label:'Tentar novamente'});
 else content.innerHTML=empty('Nenhum Provider com busca de catálogo está ativo','Ative ou instale um Provider compatível para pesquisar obras.',{key:'providers',label:'Configurar Providers'});
 }
 function drawResults(){
  // Keyed sections keep rail scroll positions and never move an existing group.
  let note=content.querySelector('.progress-note');
  if(!note){content.replaceChildren();note=document.createElement('p');note.className='progress-note';note.setAttribute('role','status');content.append(note)}
  note.textContent=state.pending?'Buscando mais resultados…':'';
  const groups=new Map();state.visible.forEach(item=>{const key=formatOrder.includes(item.format)?item.format:'Outros';if(!groups.has(key))groups.set(key,[]);groups.get(key).push(item)});
  const ordered=[...formatOrder,'Outros'].filter(key=>groups.has(key));
  ordered.forEach(name=>{
   let section=[...content.querySelectorAll('.result-section')].find(n=>n.dataset.format===name);
   if(!section){section=document.createElement('section');section.className='discovery-section result-section';section.dataset.format=name;section.innerHTML='<div class="section-head"><h2>'+esc(name)+'</h2></div><div class="catalog-rail od-rail"></div>';content.append(section)}
   const rail=section.querySelector('.catalog-rail');
   groups.get(name).forEach(item=>{
    const existing=[...rail.children].find(n=>n.dataset.work===item.id);
    const html=card(item,true);
    if(existing){const template=document.createElement('template');template.innerHTML=html;existing.replaceChildren(...template.content.firstElementChild.childNodes)}
    else rail.insertAdjacentHTML('beforeend',html);
   });
  });
 }
 function finishProgress(matches){const generation=state.generation;state.visible=matches.slice(0,1).map(i=>({...i,ts:null,scores:[]}));state.mode='results';state.pending=true;draw();
 timers.push(setTimeout(()=>{if(generation!==state.generation)return;state.visible=[...matches];draw()},1100));
 timers.push(setTimeout(()=>{if(generation!==state.generation)return;state.pending=false;draw()},2200));
 }
 function submit(){cancel();state.query=input.value.trim();input.value=state.query;
 if(cfg.mode==='providers'){state.mode='providers';draw();return}
 if(!state.query){state.mode='discovery';state.pending=false;draw();return}
 state.recents=[state.query,...state.recents.filter(q=>q.toLowerCase()!==state.query.toLowerCase())].slice(0,20);state.mode='loading';draw();const generation=state.generation;
 timers.push(setTimeout(()=>{if(generation!==state.generation)return;const matches=items.filter(i=>i.title.toLowerCase().includes(state.query.toLowerCase()));if(!matches.length){state.mode='empty';draw();return}finishProgress(matches)},700));
 }
 app.querySelector('form').addEventListener('submit',e=>{e.preventDefault();submit()});
 input.addEventListener('input',()=>{clear.hidden=!input.value;if(!input.value){cancel();state.query='';state.mode=cfg.mode==='providers'?'providers':'discovery';state.pending=false;draw()}});
 clear.addEventListener('click',()=>{input.value='';submit();input.focus({preventScroll:true})});
 phone.addEventListener('click',e=>{const button=e.target.closest('button');if(!button)return;
 if(button.dataset.query){input.value=button.dataset.query;submit();return}
 if(button.dataset.work){const entry=items.find(i=>i.id===button.dataset.work);say('Destino: detalhe canônico de '+entry.title+'. A identidade será resolvida pelo fluxo real.');return}
 if(button.dataset.action==='clear-recents'){state.recents=[];draw();return}
 if(button.dataset.action==='retry'){submit();return}
 if(button.dataset.action==='providers'){say('Destino: Configurações → Providers');return}
 if(button.dataset.navName==='Biblioteca'){say('Destino: Biblioteca');return}
 if(button.dataset.navName&&button.dataset.navName!=='Busca')say('Destino: '+button.dataset.navName);
 });
 draw();if(cfg.mode==='results'&&!cfg.longTitle){// Delayed additions remain within the same useful result surface.
  const generation=state.generation;timers.push(setTimeout(()=>{if(generation!==state.generation)return;state.visible=[...resultFixtures];draw()},14000));timers.push(setTimeout(()=>{if(generation!==state.generation)return;state.pending=false;draw()},20000));
 }
}
configs.forEach(cfg=>{const fig=document.createElement('figure');fig.className='library-figure'+(cfg.primary?' is-primary':'');fig.innerHTML='<figcaption class="library-caption">'+esc(cfg.label)+'</figcaption>';board.querySelector('.library-frames').append(fig);init(fig,cfg)});
document.body.className='search-library-entry';const library=document.querySelector('#library-board');library.before(board);document.title='Busca · Tsuzuki Default Skin';
library.querySelector('.library-heading').textContent='Biblioteca · polish final';
library.querySelectorAll('.library-figure').forEach(fig=>{const name=fig.querySelector('figcaption').textContent;fig.classList.toggle('is-review-empty',name==='Nenhum resultado'||name==='Biblioteca vazia')});
})();


