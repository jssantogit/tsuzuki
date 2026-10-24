/* Seven independent design states. Android authentication and preferences are not executed. */
(function(){
 const reference=document.querySelector('.storage-board .phone-frame');
 if(!reference)return;
 const board=document.createElement('section');board.className='privacy-board';board.setAttribute('aria-label','Privacidade e segurança — estados');
 const esc=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const icon=path=>'<svg class="dl-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="'+path+'"/></svg>';
 const back=icon('m15 18-6-6 6-6M9 12h12'),chevron=icon('m9 6 6 6-6 6'),close=icon('m6 6 12 12M18 6 6 18'),check=icon('m5 12 4 4L19 6');
 const intervals=[['0','Sempre'],['1','1 minuto'],['2','2 minutos'],['5','5 minutos'],['10','10 minutos'],['-1','Nunca']];
 const secureModes=[['always','Sempre'],['incognito','Modo anônimo'],['never','Nunca']];
 const p2pHelp='Permite conexões P2P diretas quando um Provider precisar. Seu endereço IP pode ficar visível para outros participantes da rede.';
 const consentText='Ao usar P2P direto, o Tsuzuki pode se conectar diretamente a outros participantes da rede. Eles podem ver o endereço IP da sua conexão.';
 const fixtures=[
  {name:'Estado padrão'},
  {name:'Bloqueio habilitado',lock:true,delay:'5'},
  {name:'Bloquear após',lock:true,delay:'5',sheet:'delay'},
  {name:'Tela segura',sheet:'secure'},
  {name:'Confirmação P2P',sheet:'p2p'},
  {name:'P2P direto habilitado',p2p:true},
  {name:'Dados e telemetria',telemetry:true}
 ];
 const field=(label,value='',help='')=>'<span class="dl-field od-field od-fill"><span class="dl-label">'+esc(label)+'</span>'+(value?'<span class="dl-value">'+esc(value)+'</span>':'')+(help?'<span class="dl-help">'+esc(help)+'</span>':'')+'</span>';
 const section=(label,content)=>'<section class="dl-section"><h2>'+esc(label)+'</h2><div class="dl-group">'+content+'</div></section>';
 fixtures.forEach((fixture,index)=>{
  const phone=reference.cloneNode(true);phone.querySelectorAll('[id]').forEach(node=>node.removeAttribute('id'));
  phone.querySelectorAll('.dl-overlay').forEach(node=>node.remove());phone.dataset.themeEffective='light';
  const mount=phone.querySelector('.phone-content');mount.replaceChildren();
  const figure=document.createElement('figure');figure.setAttribute('aria-label',fixture.name);figure.append(phone);board.append(figure);
  const app=document.createElement('div');app.className='dl-app ps-app od-screen';mount.append(app);
  const live=document.createElement('div');live.className='dl-live';live.hidden=true;live.setAttribute('role','status');mount.append(live);
  const overlay=document.createElement('div');overlay.className='dl-overlay';overlay.hidden=true;phone.querySelector('.phone-screen').append(overlay);
  const state={authSupported:true,telemetryIncluded:!!fixture.telemetry,lock:!!fixture.lock,delay:fixture.delay||'0',secure:'incognito',notifications:false,p2p:!!fixture.p2p,crash:true,usage:true};
  let opener=null,liveTimer;
  function announce(message){clearTimeout(liveTimer);live.textContent=message;live.hidden=false;liveTimer=setTimeout(()=>live.hidden=true,4000)}
  const toggle=(key,label,help='')=>'<div class="dl-row od-row">'+field(label,'',help)+'<button type="button" class="dl-switch od-fixed" role="switch" aria-label="'+esc(label)+'" aria-checked="'+state[key]+'" data-toggle="'+key+'"></button></div>';
  const row=(action,label,value,help='',disabled=false)=>'<button type="button" class="dl-row od-row" data-action="'+action+'" '+(disabled?'disabled ':'')+'>'+field(label,value,help)+chevron+'</button>';
  function render(){
   const position=app.querySelector('.dl-scroll')?.scrollTop||0;
   app.innerHTML='<header class="dl-header"><button type="button" class="dl-back" data-back>'+back+'<span>Configurações</span></button><h1>Privacidade e segurança</h1></header><div class="dl-scroll od-scroll"></div>';
   let security='';
   if(state.authSupported){
    security+=toggle('lock','Bloqueio do app','Exige a autenticação do dispositivo para abrir o Tsuzuki.');
    security+=row('delay','Bloquear após',intervals.find(([value])=>value===state.delay)[1],state.lock?'':'Ative o bloqueio do app para alterar.',!state.lock);
   }
   security+=toggle('notifications','Ocultar conteúdo das notificações','Oculta informações sensíveis nas notificações.');
   security+=row('secure','Tela segura',secureModes.find(([value])=>value===state.secure)[1],'Oculta a interface na troca de apps e impede capturas de tela.');
   const scroll=app.querySelector('.dl-scroll');
   scroll.innerHTML=section('Segurança',security)+section('Privacidade de rede',toggle('p2p','P2P direto',p2pHelp))+(state.telemetryIncluded?section('Dados e telemetria',toggle('crash','Relatórios de falhas','Ajuda a identificar problemas e falhas do aplicativo.')+toggle('usage','Dados de uso do app','Compartilha dados de uso para ajudar a melhorar o Tsuzuki.')):'');
   scroll.scrollTop=position;
   app.querySelector('[data-back]').onclick=()=>announce('Destino: Configurações. A referência existente permanece preservada.');
   app.querySelectorAll('[data-action]').forEach(button=>button.onclick=()=>openSheet(button.dataset.action,button));
   app.querySelectorAll('[data-toggle]').forEach(button=>button.onclick=()=>{
    const key=button.dataset.toggle;
    if(key==='p2p'&&!state.p2p){openSheet('p2p',button);return}
    state[key]=!state[key];render();app.querySelector('[data-toggle="'+key+'"]').focus({preventScroll:true});
    if(key==='lock')announce('Demonstração local. No Android, a alteração exige autenticação do dispositivo.');
   });
  }
  function dismiss(){overlay.hidden=true;overlay.replaceChildren();app.inert=false;if(opener?.isConnected)opener.focus({preventScroll:true})}
  function openSheet(kind,origin){
   if(kind==='delay'&&(!state.authSupported||!state.lock))return;
   opener=origin||app.querySelector(kind==='p2p'?'[data-toggle="p2p"]':'[data-action="'+kind+'"]');
   app.inert=true;overlay.hidden=false;
   const title=kind==='p2p'?'Permitir P2P direto?':kind==='delay'?'Bloquear após':'Tela segura';
   const titleId='ps-sheet-'+index;
   const options=kind==='delay'?intervals:secureModes;
   const value=kind==='delay'?state.delay:state.secure;
   overlay.innerHTML='<section class="dl-sheet" role="dialog" aria-modal="true" aria-labelledby="'+titleId+'" '+(kind==='p2p'?'aria-describedby="ps-consent-'+index+'"':'')+'><header class="dl-sheet-head"><div class="dl-sheet-heading"><h2 id="'+titleId+'">'+title+'</h2><button type="button" class="dl-close" aria-label="Fechar">'+close+'</button></div></header>'+(kind==='p2p'?'<div class="dl-options"><p id="ps-consent-'+index+'">'+consentText+'</p></div><footer class="dl-sheet-footer"><button type="button" class="dl-action" data-cancel>Cancelar</button><button type="button" class="dl-action dl-primary" data-permit>Permitir</button></footer>':'<div class="dl-options" role="radiogroup" aria-label="'+title+'">'+options.map(([id,label])=>'<button type="button" class="dl-option" role="radio" aria-checked="'+(id===value)+'" data-choice="'+id+'"><span>'+label+'</span><span class="dl-state">'+(id===value?check:'')+'</span></button>').join('')+'</div>')+'</section>';
   overlay.querySelector('.dl-close').onclick=dismiss;
   if(kind==='p2p'){
    overlay.querySelector('[data-cancel]').onclick=dismiss;
    overlay.querySelector('[data-permit]').onclick=()=>{state.p2p=true;dismiss();render();app.querySelector('[data-toggle="p2p"]').focus({preventScroll:true})};
   }else overlay.querySelectorAll('[data-choice]').forEach(button=>button.onclick=()=>{
    if(kind==='delay'&&!state.lock)return;
    state[kind==='delay'?'delay':'secure']=button.dataset.choice;dismiss();render();app.querySelector('[data-action="'+kind+'"]').focus({preventScroll:true});
    if(kind==='delay')announce('Demonstração local. No Android, alterar o intervalo exige autenticação do dispositivo.');
   });
   (overlay.querySelector('[data-cancel]')||overlay.querySelector('[aria-checked="true"]')||overlay.querySelector('.dl-close')).focus({preventScroll:true});
  }
  overlay.onclick=event=>{if(event.target===overlay)dismiss()};
  phone.addEventListener('keydown',event=>{
   if(overlay.hidden)return;
   if(event.key==='Escape'){event.preventDefault();dismiss();return}
   if(event.key==='Tab'){
    const controls=[...overlay.querySelectorAll('button:not(:disabled)')],first=controls[0],last=controls.at(-1);
    if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}
   }
   if(['ArrowDown','ArrowUp'].includes(event.key)){
    const radios=[...overlay.querySelectorAll('[role="radio"]')],at=radios.indexOf(document.activeElement);
    if(at>=0){event.preventDefault();radios[(at+(event.key==='ArrowDown'?1:-1)+radios.length)%radios.length].focus()}
   }
  });
  render();if(fixture.sheet)openSheet(fixture.sheet);
 });
 document.body.className='privacy-comparison';document.body.append(board);document.title='Privacidade e segurança · Tsuzuki';
})();
