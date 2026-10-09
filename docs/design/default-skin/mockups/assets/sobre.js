/* AboutLibraries supplies the full catalog on Android; this board receives a documented source sample. */
(function(){
 const reference=document.querySelector('.advanced-board .phone-frame');if(!reference)return;
 const board=document.createElement('section');board.className='about-board';board.setAttribute('aria-label','Sobre e fechamento de Configurações — sete composições');
 const esc=x=>String(x).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const icon=path=>'<svg class="dl-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="'+path+'"/></svg>';
 const back=icon('m15 18-6-6 6-6M9 12h12'),chevron=icon('m9 6 6 6-6 6'),external=icon('M14 4h6v6M20 4 10 14M10 4H4v16h16v-6'),close=icon('m6 6 12 12M18 6 6 18');
 const field=(label,help='')=>'<span class="dl-field od-field od-fill"><span class="dl-label">'+esc(label)+'</span>'+(help?'<span class="dl-help">'+esc(help)+'</span>':'')+'</span>';
 const section=(title,content)=>'<section class="dl-section"><h2>'+esc(title)+'</h2><div class="dl-group">'+content+'</div></section>';
 // Runtime adapters replace these records; neither identity nor count is a fixed product contract.
 const catalog=[{name:'AboutLibraries',version:'15.2.0',module:'com.mikepenz:aboutlibraries-compose-m3'},{name:'OkHttp',version:'5.5.0',module:'com.squareup.okhttp3:okhttp'},{name:'Kotlin Coroutines',version:'1.11.0',module:'org.jetbrains.kotlinx:kotlinx-coroutines-core'}];
 const build={channel:'Stable',version:'0.20.4',commit:null,date:null,count:null};
 function version(record){if(record.channel==='Debug')return 'Debug'+(record.commit?' '+record.commit:'');if(record.channel==='Nightly')return 'Nightly'+(record.count!=null?' r'+record.count:'')+(record.commit?' · '+record.commit:'');return record.channel+' v'+record.version}
 ['about','licenses'].forEach(initial=>{
  const figure=document.createElement('figure');figure.setAttribute('aria-label',initial==='about'?'Sobre':'Licenças');const phone=reference.cloneNode(true);phone.querySelectorAll('[id]').forEach(n=>n.removeAttribute('id'));phone.querySelectorAll('.dl-overlay').forEach(n=>n.remove());phone.dataset.themeEffective='light';
  const mount=phone.querySelector('.phone-content');mount.replaceChildren();figure.append(phone);board.append(figure);
  const app=document.createElement('div');app.className='dl-app od-screen ab-app';mount.append(app);const overlay=document.createElement('div');overlay.className='dl-overlay';overlay.hidden=true;mount.append(overlay);const live=document.createElement('div');live.className='dl-live av-live';live.hidden=true;live.setAttribute('role','status');mount.append(live);
  let page=initial,origin=null,position=0,timer;
  function announce(text){live.textContent=text;live.hidden=false;clearTimeout(timer);timer=setTimeout(()=>live.hidden=true,4000)}
  function render(){
   app.innerHTML='<header class="dl-header"><button type="button" class="dl-back">'+back+(page==='about'?'Configurações':'Sobre')+'</button><h1>'+(page==='about'?'Sobre':'Licenças')+'</h1></header><main class="dl-scroll od-scroll"></main>';
   const scroll=app.querySelector('.dl-scroll');
   if(page==='about'){
    scroll.innerHTML='<div class="ab-brand"><img class="od-media" src="assets/sobre/tsuzuki-lockup.svg" width="294" height="64" alt="Tsuzuki"><div class="ab-version"><p>'+esc(version(build))+'</p>'+(build.date?'<p>'+esc(build.date)+'</p>':'')+'</div></div>'+section('Projeto','<a class="dl-row od-row" href="https://github.com/jssantogit/tsuzuki" target="_blank" rel="noopener noreferrer">'+field('Código-fonte do Tsuzuki','Veja o projeto e seu código-fonte no GitHub.')+external+'</a><a class="dl-row od-row" href="https://github.com/mihonapp/mihon" target="_blank" rel="noopener noreferrer">'+field('Projeto upstream — Mihon','Tsuzuki é desenvolvido a partir do projeto open source Mihon.')+external+'</a>')+section('Código aberto','<button type="button" class="dl-row od-row" data-licenses>'+field('Licenças','Bibliotecas e componentes de código aberto utilizados pelo Tsuzuki.')+chevron+'</button>');
    scroll.scrollTop=position;app.querySelector('[data-licenses]').onclick=()=>{position=scroll.scrollTop;page='licenses';render();app.querySelector('.dl-back').focus({preventScroll:true})};
   }else{
    scroll.innerHTML='<div class="dl-group">'+catalog.map((library,i)=>'<button type="button" class="ab-license-row od-row" data-library="'+i+'">'+field(library.name,library.version)+chevron+'</button>').join('')+'</div>';
    app.querySelectorAll('[data-library]').forEach(button=>button.onclick=()=>open(catalog[Number(button.dataset.library)],button));
   }
   app.querySelector('.dl-back').onclick=()=>{if(page==='licenses'){page='about';render();app.querySelector('[data-licenses]').focus({preventScroll:true})}else announce('Retorno a Configurações; a referência existente permanece preservada.')};
  }
  function dismiss(){overlay.hidden=true;overlay.replaceChildren();app.inert=false;if(origin?.isConnected)origin.focus({preventScroll:true})}
  function open(library,button){
   origin=button;app.inert=true;overlay.hidden=false;const title='ab-detail-'+initial;
   overlay.innerHTML='<section class="dl-sheet" role="dialog" aria-modal="true" aria-labelledby="'+title+'"><header class="dl-sheet-head"><div class="dl-sheet-heading"><h2 id="'+title+'">'+esc(library.name)+'</h2><button type="button" class="dl-close" aria-label="Fechar">'+close+'</button></div></header><div class="dl-options ab-detail"><p>'+esc(library.version)+'</p><p>'+esc(library.module)+'</p><p>O catálogo gerado do aplicativo fornece os textos de licença desta biblioteca.</p></div><footer class="dl-sheet-footer"><button type="button" class="dl-action" data-close>Fechar</button></footer></section>';
   overlay.querySelectorAll('.dl-close,[data-close]').forEach(b=>b.onclick=dismiss);overlay.querySelector('[data-close]').focus({preventScroll:true});
  }
  overlay.onclick=e=>{if(e.target===overlay)dismiss()};phone.addEventListener('keydown',e=>{if(overlay.hidden)return;if(e.key==='Escape'){e.preventDefault();dismiss()}if(e.key==='Tab'){const controls=[...overlay.querySelectorAll('button')],first=controls[0],last=controls.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus()}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus()}}});render();
 });
 // Move live instances so their handlers and state remain intact, rather than cloning controls.
 const diagnostic=document.querySelector('.diagnostic-board'),advanced=document.querySelector('.advanced-board');
 const diagnosticFrames=diagnostic?[...diagnostic.children]:[],advancedFrames=advanced?[...advanced.children]:[];
 [diagnosticFrames[0],diagnosticFrames[4],advancedFrames[5],advancedFrames[3],advancedFrames[6]].filter(Boolean).forEach(figure=>board.append(figure));
 document.body.className='about-comparison';document.body.append(board);document.title='Sobre · Tsuzuki';
})();
