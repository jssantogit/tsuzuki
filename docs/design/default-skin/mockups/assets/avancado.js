/* Five independent design fixtures. Android intents, jobs and database actions are not executed. */
(function(){
 const reference=document.querySelector('.diagnostic-board .phone-frame');if(!reference)return;
 const board=document.createElement('section');board.className='advanced-board';board.setAttribute('aria-label','Avançado — cinco composições');
 const esc=x=>String(x).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const icon=path=>'<svg class="dl-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="'+path+'"/></svg>';
 const back=icon('m15 18-6-6 6-6M9 12h12'),chevron=icon('m9 6 6 6-6 6'),external=icon('M14 4h6v6M20 4 10 14M10 4H4v16h16v-6'),close=icon('m6 6 12 12M18 6 6 18'),dots=icon('M12 5h.01M12 12h.01M12 19h.01');
 const dnsOptions=['Desativado','Cloudflare','Google','AdGuard','Quad9','AliDNS','DNSPod','360','Quad 101','Mullvad','Control D','Njalla','Shecan'];
 const defaultAgent='Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36';
 const field=(label,help='',value='')=>'<span class="dl-field od-field od-fill"><span class="dl-label">'+esc(label)+'</span>'+(value?'<span class="dl-value">'+esc(value)+'</span>':'')+(help?'<span class="dl-help">'+esc(help)+'</span>':'')+'</span>';
 const section=(title,content)=>'<section class="dl-section"><h2>'+esc(title)+'</h2><div class="dl-group">'+content+'</div></section>';
 const row=(action,label,help='',value='',out=false)=>'<button type="button" class="dl-row od-row" data-action="'+action+'">'+field(label,help,value)+(out?external:(["cookies","webview"].includes(action)?"":chevron))+'</button>';
 const fixtures=[{page:'main'},{page:'main',sheet:'dns'},{page:'main',sheet:'agent',custom:true},{page:'clean'},{page:'clean',sheet:'remove'},{page:'main',lower:true},{page:'clean',sheet:'remove',risk:true}];
 fixtures.forEach((fixture,index)=>{
  const figure=document.createElement('figure');figure.setAttribute('aria-label',['Avançado','DNS sobre HTTPS','User Agent padrão','Limpar dados fora da biblioteca','Confirmação da limpeza','Avançado — parte inferior','Confirmação da limpeza — proteção desativada'][index]);
  const phone=reference.cloneNode(true);phone.querySelectorAll('[id]').forEach(n=>n.removeAttribute('id'));phone.querySelectorAll('.dl-overlay').forEach(n=>n.remove());
  const mount=phone.querySelector('.phone-content');mount.replaceChildren();figure.append(phone);board.append(figure);
  const app=document.createElement('div');app.className='dl-app od-screen av-app';mount.append(app);
  const overlay=document.createElement('div');overlay.className='dl-overlay';overlay.hidden=true;mount.append(overlay);
  const live=document.createElement('div');live.className='dl-live av-live';live.setAttribute('role','status');live.hidden=true;mount.append(live);
  const state={page:fixture.page,dns:'Desativado',agent:fixture.custom?'Tsuzuki/1.0':defaultAgent,renderer:true,ascii:false,selected:new Set(fixture.sheet==='remove'?['mangafire','mangadex']:[]),entries:[{id:'mangafire',name:'MangaFire',count:24,read:4},{id:'mangadex',name:'MangaDex',count:12,read:2},{id:'mangaball',name:'MangaBall',count:5,read:0}]};
  let origin=null,toastTimer,position=0;
  function announce(text){live.textContent=text;live.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>live.hidden=true,5000)}
  function toggle(key,label,help=''){return '<div class="dl-row od-row">'+field(label,help)+'<button type="button" class="dl-switch od-fixed" role="switch" aria-checked="'+state[key]+'" aria-label="'+esc(label)+'" data-toggle="'+key+'"></button></div>'}
  function render(){
   const clean=state.page==='clean';app.classList.toggle('av-with-footer',clean);
   app.innerHTML='<header class="dl-header"><button type="button" class="dl-back" data-back>'+back+(clean?'Avançado':'Configurações')+'</button><div class="av-header-line"><h1>'+(clean?'Limpar dados fora da biblioteca':'Avançado')+'</h1>'+(clean?'<div class="av-menu-anchor"><button type="button" class="av-small" data-menu aria-label="Ações de seleção" aria-expanded="false">'+dots+'</button><div class="av-menu" hidden><button type="button" data-action="all">Selecionar tudo</button><button type="button" data-action="invert">Inverter seleção</button></div></div>':'')+'</div></header><main class="dl-scroll"></main>'+(clean?'<footer class="av-footer"><button type="button" class="dl-action av-danger" data-action="remove" '+(state.selected.size?'':'disabled')+'>Remover</button></footer>':'');
   const scroll=app.querySelector('.dl-scroll');
   if(clean){
    const entries=state.entries.filter(e=>e.count>0);
    scroll.innerHTML=entries.length?'<div class="dl-group">'+entries.map(e=>'<label class="dl-row od-row av-check-row">'+field(e.name,'',e.count+(e.count===1?' entrada':' entradas'))+'<input class="av-check" type="checkbox" data-select="'+e.id+'" '+(state.selected.has(e.id)?'checked':'')+' aria-label="Selecionar '+esc(e.name)+'"></label>').join('')+'</div>':'<div class="av-empty"><strong>Nenhum dado para limpar</strong></div>';
   }else{
    scroll.innerHTML=section('Sistema',row('notifications','Notificações','Gerencie as notificações do Tsuzuki nas configurações do dispositivo.','',true)+row('battery','Desativar otimização de bateria','Ajuda a evitar interrupções em atualizações, downloads e outras tarefas em segundo plano.','',true)+'<a class="dl-row od-row" href="https://dontkillmyapp.com/" target="_blank" rel="noopener noreferrer">'+field('Ajuda com atividade em segundo plano','Veja orientações específicas para dispositivos que limitam tarefas em segundo plano.')+external+'</a>')+section('Rede',row('dns','DNS sobre HTTPS','',state.dns)+row('agent','User Agent padrão','',state.agent===defaultAgent?'Padrão':'Personalizado')+row('cookies','Limpar cookies','Remove cookies das sessões web utilizadas pelo aplicativo.')+row('webview','Limpar dados do WebView','Remove dados das sessões web utilizadas pelo aplicativo.'))+section('Manutenção',row('reindex','Reindexar downloads','Faz o Tsuzuki verificar novamente os capítulos disponíveis offline.')+row('covers','Atualizar capas da biblioteca')+row('reader','Redefinir configurações do leitor em cada série','Redefine o modo de leitura e a orientação salvos individualmente.')+row('clean','Limpar dados fora da biblioteca','Remove entradas armazenadas de obras que não fazem parte da sua biblioteca.'))+section('Compatibilidade',toggle('renderer','Usar renderizador de alta qualidade')+toggle('ascii','Usar apenas nomes de arquivo ASCII','Melhora a compatibilidade com mídias de armazenamento que não suportam nomes Unicode.'));
   }
   scroll.scrollTop=position;
   app.querySelector('[data-back]').onclick=()=>{if(clean){state.page='main';position=0;render();app.querySelector('[data-back]').focus({preventScroll:true})}else announce('Retorno a Configurações; as referências anteriores permanecem preservadas.')};
   app.querySelectorAll('[data-toggle]').forEach(button=>button.onclick=()=>{state[button.dataset.toggle]=!state[button.dataset.toggle];button.setAttribute('aria-checked',state[button.dataset.toggle])});
   app.querySelectorAll('[data-action]').forEach(button=>button.onclick=()=>action(button.dataset.action,button));
   app.querySelectorAll('[data-select]').forEach(input=>input.onchange=()=>{input.checked?state.selected.add(input.dataset.select):state.selected.delete(input.dataset.select);app.querySelector('[data-action="remove"]').disabled=!state.selected.size});
   const menu=app.querySelector('[data-menu]');if(menu)menu.onclick=()=>{const panel=app.querySelector('.av-menu');panel.hidden=!panel.hidden;menu.setAttribute('aria-expanded',!panel.hidden)};
  }
  function action(kind,button){
   if(['dns','agent','remove'].includes(kind)){open(kind,button);return}
   if(kind==='clean'){position=0;state.page='clean';render();app.querySelector('[data-back]').focus({preventScroll:true});return}
   if(kind==='all'||kind==='invert'){
    position=app.querySelector('.dl-scroll').scrollTop;state.entries.filter(e=>e.count>0).forEach(e=>{if(kind==='all'||!state.selected.has(e.id))state.selected.add(e.id);else state.selected.delete(e.id)});render();app.querySelector('[data-menu]').focus({preventScroll:true});return;
   }
   const feedback={notifications:'Demonstração: abrir as configurações de notificações do Android.',battery:'Demonstração: abrir o fluxo de otimização de bateria do Android; se já desativada, informar esse estado.',cookies:'Demonstração: limpar cookies das sessões web. Nenhum dado real foi removido.',webview:'Demonstração: limpar dados do WebView. Nenhum dado real foi removido.',reindex:'Demonstração: solicitar nova verificação dos capítulos offline, sem excluir downloads.',covers:'Demonstração: solicitar atualização das capas da biblioteca.',reader:'Demonstração: redefinir modo de leitura e orientação individuais, sem alterar preferências globais.'};
   announce(feedback[kind]||'');
  }
  function dismiss(){overlay.hidden=true;overlay.replaceChildren();app.inert=false;if(origin?.isConnected)origin.focus({preventScroll:true})}
  function open(kind,button){
   if(kind==='remove'&&!state.selected.size)return;
   origin=button||app.querySelector('[data-action="'+kind+'"]');app.inert=true;overlay.hidden=false;
   const id='av-dialog-'+index,title={dns:'DNS sobre HTTPS',agent:'User Agent padrão',remove:'Remover dados selecionados?'}[kind];
   let content='',footer='';
   if(kind==='dns'){content='<div role="radiogroup" aria-label="Provedor DNS">'+dnsOptions.map((name,i)=>'<button type="button" class="dl-option" role="radio" aria-checked="'+(name===state.dns)+'" data-dns="'+i+'"><span>'+esc(name)+'</span><span class="av-radio" aria-hidden="true"></span></button>').join('')+'</div>';footer='<button type="button" class="dl-action" data-cancel>Cancelar</button>'}
   if(kind==='agent'){content='<div class="av-form"><label for="'+id+'-input">User Agent</label><textarea id="'+id+'-input" spellcheck="false" autocomplete="off" aria-describedby="'+id+'-error">'+esc(state.agent)+'</textarea><p class="av-error" id="'+id+'-error" hidden>Use caracteres válidos para um header HTTP, sem quebras de linha.</p>'+(state.agent!==defaultAgent?'<button type="button" class="av-reset" data-reset>Redefinir padrão</button>':'')+'</div>';footer='<button type="button" class="dl-action" data-cancel>Cancelar</button><button type="button" class="dl-action dl-primary" data-save>Salvar</button>'}
   if(kind==='remove'){content='<p>As entradas fora da biblioteca selecionadas serão removidas.</p><div class="dl-group"><div class="dl-row od-row">'+field('Manter obras com capítulos lidos')+'<button type="button" class="dl-switch" role="switch" aria-checked="true" aria-label="Manter obras com capítulos lidos" data-keep></button></div></div><p class="av-warning" hidden>Capítulos lidos e o progresso de entradas fora da biblioteca poderão ser perdidos.</p>';footer='<button type="button" class="dl-action" data-cancel>Cancelar</button><button type="button" class="dl-action dl-primary av-danger" data-confirm>Remover</button>'}
   overlay.innerHTML='<section class="dl-sheet av-dialog" role="dialog" aria-modal="true" aria-labelledby="'+id+'"><header class="dl-sheet-head"><div class="dl-sheet-heading"><h2 id="'+id+'">'+title+'</h2><button type="button" class="dl-close" aria-label="Fechar">'+close+'</button></div></header><div class="dl-options">'+content+'</div><footer class="dl-sheet-footer">'+footer+'</footer></section>';
   overlay.querySelector('.dl-close').onclick=dismiss;overlay.querySelector('[data-cancel]').onclick=dismiss;
   if(kind==='dns'){
    const choices=[...overlay.querySelectorAll('[data-dns]')];
    choices.forEach((b,i)=>{b.onclick=()=>{const next=dnsOptions[i],changed=state.dns!==next;state.dns=next;position=app.querySelector('.dl-scroll').scrollTop;dismiss();render();app.querySelector('[data-action="dns"]').focus({preventScroll:true});if(changed)announce('Reinicie o aplicativo para aplicar a alteração.')};b.onkeydown=e=>{if(['ArrowDown','ArrowRight','ArrowUp','ArrowLeft','Home','End'].includes(e.key)){e.preventDefault();const next=e.key==='Home'?0:e.key==='End'?choices.length-1:(i+(['ArrowDown','ArrowRight'].includes(e.key)?1:-1)+choices.length)%choices.length;choices[next].focus()}}});
   }
   if(kind==='agent'){
    const input=overlay.querySelector('textarea'),error=overlay.querySelector('.av-error');
    const validate=()=>{const valid=!/[^\t\x20-\x7E]/.test(input.value);input.setAttribute('aria-invalid',!valid);error.hidden=valid;return valid};input.onblur=validate;
    const commit=value=>{const changed=state.agent!==value;state.agent=value;position=app.querySelector('.dl-scroll').scrollTop;dismiss();render();app.querySelector('[data-action="agent"]').focus({preventScroll:true});if(changed)announce('Reinicie o aplicativo para aplicar a alteração.')};
    overlay.querySelector('[data-save]').onclick=()=>{if(validate())commit(input.value);else input.focus()};const reset=overlay.querySelector('[data-reset]');if(reset)reset.onclick=()=>commit(defaultAgent);
   }
   if(kind==='remove'){
    let keep=true;const control=overlay.querySelector('[data-keep]');control.onclick=()=>{keep=!keep;control.setAttribute('aria-checked',keep);overlay.querySelector('.av-warning').hidden=keep};
    overlay.querySelector('[data-confirm]').onclick=()=>{state.entries.forEach(e=>{if(state.selected.has(e.id)){e.count=keep?e.read:0;if(!keep)e.read=0}});state.selected.clear();position=0;dismiss();render();app.querySelector('[data-back]').focus({preventScroll:true});announce('Demonstração concluída. Nenhuma entrada real foi removida.')};
   }
   overlay.querySelector('[data-cancel]').focus({preventScroll:true});
  }
  overlay.onclick=e=>{if(e.target===overlay)dismiss()};
  phone.addEventListener('click',e=>{const anchor=app.querySelector('.av-menu-anchor');if(anchor&&!anchor.contains(e.target)){app.querySelector('.av-menu').hidden=true;app.querySelector('[data-menu]').setAttribute('aria-expanded','false')}});
  phone.addEventListener('keydown',e=>{
   if(!overlay.hidden){if(e.key==='Escape'){e.preventDefault();dismiss()}if(e.key==='Tab'){const controls=[...overlay.querySelectorAll('button:not(:disabled),textarea')],first=controls[0],last=controls.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus()}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus()}}}
   else if(e.key==='Escape'){const panel=app.querySelector('.av-menu');if(panel&&!panel.hidden){panel.hidden=true;const menu=app.querySelector('[data-menu]');menu.setAttribute('aria-expanded','false');menu.focus()}}
  });
  render();if(fixture.sheet){open(fixture.sheet);if(fixture.risk)overlay.querySelector('[data-keep]').click()}if(fixture.lower)requestAnimationFrame(()=>{const scroll=app.querySelector('.dl-scroll'),section=scroll.querySelectorAll('.dl-section')[2];scroll.scrollTop+=section.getBoundingClientRect().top-scroll.getBoundingClientRect().top});
 });
 document.body.className='advanced-comparison';document.body.append(board);document.title='Avançado · Tsuzuki';
})();
