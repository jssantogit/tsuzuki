/* Downloads design scenarios. No Android preference, download, or cleanup operations. */
(function(){
 const reference=document.querySelector('.providers-board .phone-frame');if(!reference)return;
 const board=document.createElement('section');board.className='downloads-board';board.setAttribute('aria-label','Família Downloads');
 const svg=path=>'<svg class="dl-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="'+path+'"/></svg>';
 const back=svg('m15 18-6-6 6-6M9 12h12'),chevron=svg('m9 6 6 6-6 6'),close=svg('m6 6 12 12M18 6 6 18'),check=svg('m5 12 4 4L19 6'),minus=svg('M6 12h12');
 const esc=v=>String(v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const names=['Lendo','Favoritos','Arquivados','Planejados','Para guardar'];
 const ahead=['Desativado','Próximos 2 capítulos não lidos','Próximos 3 capítulos não lidos','Próximos 5 capítulos não lidos','Próximos 10 capítulos não lidos'];
 const removal=['Desativado','Último capítulo lido','Penúltimo capítulo lido','Terceiro anterior','Quarto anterior','Quinto anterior'];
 const field=(label,value,help)=>'<span class="dl-field od-field"><span class="dl-label">'+esc(label)+'</span>'+(value?'<span class="dl-value">'+esc(value)+'</span>':'')+(help?'<span class="dl-help">'+esc(help)+'</span>':'')+'</span>';
 const section=(label,content)=>'<section class="dl-section"><h2>'+esc(label)+'</h2><div class="dl-group">'+content+'</div></section>';
 ['root','auto','cleanup','categories'].forEach((initial,index)=>{
  const figure=document.createElement('figure');figure.setAttribute('aria-label',['Downloads','Downloads automáticos','Limpeza automática','Categorias — Downloads automáticos'][index]);
  const phone=reference.cloneNode(true);phone.querySelectorAll('[id]').forEach(n=>n.removeAttribute('id'));phone.dataset.themeEffective='light';phone.removeAttribute('data-amoled');
  Object.entries({'--bg':'#F7F7F8','--surface':'#FFFFFF','--text':'#17181A','--secondary':'#50565C','--divider':'#D6D9DC','--icon-surface':'#E8EAED','--phone-screen-bg':'#F7F7F8','--phone-system-fg':'#17181A'}).forEach(([k,v])=>phone.style.setProperty(k,v));
  const mount=phone.querySelector('.phone-content');mount.replaceChildren();figure.append(phone);board.append(figure);
  const state={wifi:true,cbz:true,split:true,sources:5,pages:5,automatic:initial!=='root',unread:true,ahead:0,marked:false,remove:initial==='cleanup'?1:0,bookmarked:false,categories:{Lendo:'include',Arquivados:'exclude'},excluded:['Favoritos','Para guardar']};
  if(initial==='categories')state.categories.Favoritos='include';
  let mode=initial==='categories'?'auto':initial,positions={},sheet=null,opener=null,timer=null;
  const app=document.createElement('div');app.className='dl-app';mount.append(app);
  const overlay=document.createElement('div');overlay.className='dl-overlay';overlay.hidden=true;phone.querySelector('.phone-screen').append(overlay);
  const live=document.createElement('div');live.className='dl-live';live.setAttribute('role','status');live.hidden=true;mount.append(live);
  const toggle=(key,label,help='',disabled=false)=>'<div class="dl-row od-row" data-disabled="'+disabled+'">'+field(label,'',help)+'<button type="button" class="dl-switch od-fixed" role="switch" aria-label="'+esc(label)+'" aria-checked="'+state[key]+'" data-toggle="'+key+'" '+(disabled?'disabled':'')+'></button></div>';
  const row=(action,label,value,disabled=false)=>'<button type="button" class="dl-row od-row" data-action="'+action+'" '+(disabled?'disabled':'')+' data-disabled="'+disabled+'">'+field(label,value)+chevron+'</button>';
  const categorySummary=()=>{const included=names.filter(n=>state.categories[n]==='include'),excluded=names.filter(n=>state.categories[n]==='exclude');return(included.length?'Incluir: '+included.join(', '):'')+(included.length&&excluded.length?' · ':'')+(excluded.length?'Excluir: '+excluded.join(', '):'')||'Todas as categorias'};
  const slider=(key,label,max,help='')=>'<div class="dl-row dl-slider-row"><div class="dl-slider-title"><label for="dl-'+index+'-'+key+'">'+esc(label)+'</label><output for="dl-'+index+'-'+key+'">'+state[key]+'</output></div>'+(help?'<span class="dl-help">'+esc(help)+'</span>':'')+'<input id="dl-'+index+'-'+key+'" type="range" min="1" max="'+max+'" step="1" value="'+state[key]+'" data-slider="'+key+'"><div class="dl-scale" aria-hidden="true"><span>1</span><span>'+max+'</span></div></div>';
  function announce(message){clearTimeout(timer);live.textContent=message;live.hidden=false;timer=setTimeout(()=>live.hidden=true,3000)}
  function render(next=mode){
   const old=app.querySelector('.dl-scroll');if(old)positions[mode]=old.scrollTop;mode=next;
   const title=mode==='root'?'Downloads':mode==='auto'?'Downloads automáticos':'Limpeza automática';
   app.innerHTML='<header class="dl-header"><button type="button" class="dl-back" data-back>'+back+'<span>'+(mode==='root'?'Configurações':'Downloads')+'</span></button><h1 tabindex="-1">'+title+'</h1></header><div class="dl-scroll od-scroll"></div>';
   const scroll=app.querySelector('.dl-scroll');
   if(mode==='root')scroll.innerHTML=section('Rede e arquivos',toggle('wifi','Somente por Wi-Fi')+toggle('cbz','Salvar capítulos como CBZ')+toggle('split','Dividir imagens muito altas','Melhora o desempenho do leitor'))+section('Desempenho',slider('sources','Fontes simultâneas',10)+slider('pages','Páginas simultâneas',15,'Mais páginas por fonte podem aumentar o uso de recursos.'))+section('Automação',row('auto','Downloads automáticos',state.automatic?'Ativado':'Desativado')+row('ahead','Baixar enquanto lê',ahead[state.ahead]))+section('Limpeza',row('cleanup','Limpeza automática',state.marked||state.remove?'Ativada':'Desativada'));
   else if(mode==='auto')scroll.innerHTML=section('Novos capítulos',toggle('automatic','Baixar novos capítulos')+toggle('unread','Somente capítulos não lidos','',!state.automatic))+section('Categorias',row('categories','Categorias',categorySummary(),!state.automatic))+(!state.automatic?'<p class="dl-note">Ative Baixar novos capítulos para alterar estas opções. Suas escolhas foram mantidas.</p>':'');
   else scroll.innerHTML=section('Após leitura',toggle('marked','Remover ao marcar como lido')+row('remove','Remover após leitura',removal[state.remove]))+section('Proteção',toggle('bookmarked','Remover capítulos marcados',state.bookmarked?'Capítulos marcados também podem ser removidos.':'Capítulos marcados permanecem protegidos.'))+section('Exceções',row('excluded','Categorias excluídas',state.excluded.join(' · ')||'Nenhuma'));
   scroll.scrollTop=positions[mode]||0;
   app.querySelector('[data-back]').onclick=()=>{if(mode==='root')announce('Configurações');else{render('root');app.querySelector('h1').focus({preventScroll:true})}};
   app.querySelectorAll('[data-toggle]').forEach(button=>button.onclick=()=>{const key=button.dataset.toggle;state[key]=!state[key];if(key==='automatic'||key==='bookmarked'){render();app.querySelector('[data-toggle="'+key+'"]').focus({preventScroll:true})}else button.setAttribute('aria-checked',state[key])});
   app.querySelectorAll('[data-slider]').forEach(input=>input.oninput=()=>{state[input.dataset.slider]=Number(input.value);input.closest('.dl-slider-row').querySelector('output').value=input.value});
   app.querySelectorAll('[data-action]').forEach(button=>button.onclick=()=>{const action=button.dataset.action;if(action==='auto'||action==='cleanup'){render(action);app.querySelector('h1').focus({preventScroll:true})}else openSheet(action,button)});
  }
  function dismiss(){overlay.hidden=true;overlay.replaceChildren();sheet=null;opener?.focus({preventScroll:true})}
  function openSheet(type,origin){
   opener=origin||app.querySelector('[data-action="categories"]');
   sheet={type,draft:type==='categories'?{...state.categories}:type==='excluded'?[...state.excluded]:state[type]};
   overlay.hidden=false;drawSheet();
   overlay.querySelector('.dl-close').focus({preventScroll:true});
  }
  function drawSheet(){
   const {type,draft}=sheet,title=type==='categories'?'Categorias':type==='excluded'?'Categorias excluídas':type==='ahead'?'Baixar enquanto lê':'Remover após leitura';
   let options;
   if(type==='categories')options=names.slice(0,4).map(name=>{const value=draft[name]||'neutral',label={neutral:'Neutro',include:'Incluir',exclude:'Excluir'}[value];return'<button type="button" class="dl-option" data-name="'+name+'" data-state="'+value+'" aria-label="'+name+': '+label+'"><span>'+name+'</span><span class="dl-state">'+(value==='include'?check:minus)+'<span>'+label+'</span></span></button>'}).join('');
   else if(type==='excluded')options=names.map(name=>'<button type="button" class="dl-option" data-name="'+name+'" aria-pressed="'+draft.includes(name)+'"><span>'+name+'</span><span class="dl-state">'+(draft.includes(name)?check:'')+'<span>'+(draft.includes(name)?'Selecionada':'Selecionar')+'</span></span></button>').join('');
   else options=(type==='ahead'?ahead:removal).map((label,i)=>'<button type="button" class="dl-option" data-value="'+i+'" aria-pressed="'+(draft===i)+'"><span>'+label+'</span><span class="dl-state">'+(draft===i?check:'')+'</span></button>').join('');
   overlay.innerHTML='<section class="dl-sheet" role="dialog" aria-modal="true" aria-labelledby="dl-sheet-title-'+index+'"><header class="dl-sheet-head"><div class="dl-sheet-heading"><h2 id="dl-sheet-title-'+index+'">'+title+'</h2><button type="button" class="dl-close" aria-label="Fechar">'+close+'</button></div>'+(type==='categories'?'<p>Escolha quais categorias entram ou ficam fora dos downloads automáticos.</p>':'')+'</header><div class="dl-options">'+options+'</div>'+((type==='categories'||type==='excluded')?'<footer class="dl-sheet-footer"><button type="button" class="dl-action" data-secondary>'+(type==='categories'?'Limpar':'Cancelar')+'</button><button type="button" class="dl-action dl-primary" data-apply>'+(type==='categories'?'Aplicar':'Confirmar')+'</button></footer>':'')+'</section>';
   overlay.querySelector('.dl-close').onclick=dismiss;
   overlay.querySelectorAll('[data-name]').forEach(button=>button.onclick=()=>{const name=button.dataset.name;if(type==='categories'){const next={neutral:'include',include:'exclude',exclude:'neutral'}[draft[name]||'neutral'];if(next==='neutral')delete draft[name];else draft[name]=next}else{const i=draft.indexOf(name);if(i<0)draft.push(name);else draft.splice(i,1)}drawSheet();overlay.querySelector('[data-name="'+name+'"]').focus({preventScroll:true})});
   overlay.querySelectorAll('[data-value]').forEach(button=>button.onclick=()=>{state[type]=Number(button.dataset.value);dismiss();render();app.querySelector('[data-action="'+type+'"]').focus({preventScroll:true})});
   const secondary=overlay.querySelector('[data-secondary]');if(secondary)secondary.onclick=()=>{if(type==='excluded')dismiss();else{sheet.draft={};drawSheet();overlay.querySelector('[data-secondary]').focus({preventScroll:true})}};
   const apply=overlay.querySelector('[data-apply]');if(apply)apply.onclick=()=>{if(type==='categories')state.categories={...sheet.draft};else state.excluded=[...sheet.draft];dismiss();render();app.querySelector('[data-action="'+type+'"]').focus({preventScroll:true})};
  }
  overlay.addEventListener('click',event=>{if(event.target===overlay)dismiss()});
  phone.addEventListener('keydown',event=>{if(overlay.hidden)return;if(event.key==='Escape'){event.preventDefault();dismiss()}if(event.key==='Tab'){const controls=[...overlay.querySelectorAll('button:not(:disabled),input:not(:disabled)')];const first=controls[0],last=controls.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}}});
  render();if(initial==='categories')openSheet('categories');
 });
 document.body.className='downloads-comparison';document.body.append(board);document.title='Downloads · Tsuzuki';
})();
