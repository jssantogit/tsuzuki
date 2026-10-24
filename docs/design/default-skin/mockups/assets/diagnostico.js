/* Seven design fixtures. No Android diagnostic service is executed by this board. */
(function(){
 const reference=document.querySelector('.privacy-board .phone-frame');
 if(!reference)return;
 const board=document.createElement('section');board.className='diagnostic-board';board.setAttribute('aria-label','Diagnóstico — sete composições');
 const esc=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const icon=path=>'<svg class="dl-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="'+path+'"/></svg>';
 const back=icon('m15 18-6-6 6-6M9 12h12'),chevron=icon('m9 6 6 6-6 6'),close=icon('m6 6 12 12M18 6 6 18'),renew=icon('M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 3M4 15l2 3a7 7 0 0 0 12-1');
 const privacy='Revise o relatório antes de compartilhá-lo. Logs e informações de falha podem conter dados fornecidos por serviços ou componentes externos.';
 const clearCopy='Os diagnósticos armazenados pelo Tsuzuki e o último crash salvo serão apagados.';
 const field=(label,value='',help='')=>'<span class="dl-field od-field od-fill"><span class="dl-label">'+esc(label)+'</span>'+(value?'<span class="dl-value">'+esc(value)+'</span>':'')+(help?'<span class="dl-help">'+esc(help)+'</span>':'')+'</span>';
 const section=(title,content)=>'<section class="dl-section"><h2>'+esc(title)+'</h2><div class="dl-group">'+content+'</div></section>';
 const row=(action,label,help='',value='',extra='',arrow=false)=>'<button type="button" class="dl-row od-row '+extra+'" data-action="'+action+'">'+field(label,value,help)+(arrow?chevron:'')+'</button>';
 const info=(label,value)=>'<div class="dl-row od-row">'+field(label,value)+'</div>';
 // These are presentation examples, not values read from an Android device.
 const technical={version:'0.20.4',build:'09 out 2026 · 10:24',installationId:'34f92c06-8d7a-4b6d-9c62-b2e89d5730af',profile:'compiled',webview:'140.0.7339.207',model:'Google Pixel 8 (shiba)',android:'15 (AP3A.241105.007)',oneUi:null,miui:null};
 const profileLabel=record=>({none:'Sem perfil instalado',compiled:'Compilado',nonMatching:'Compilado com perfil divergente',unsupported:'Não suportado',pending:'Compilação pendente',noEmbedded:'Sem perfil incorporado'}[record.profile]||(record.profile==='error'?'Erro '+record.profileCode:'Código desconhecido '+record.profileCode));
 const workers={running:[{id:'8fb3cc40-7d1f-4a28-b45a-03c44284d81a',tags:['LibraryUpdate'],status:'RUNNING'}],enqueued:[{id:'e2017a68-393e-4abc-a162-d04d1fba8220',tags:['AutomaticBackup'],status:'ENQUEUED',next:'10 out 2026 · 10:00',attempt:1}],finished:[]};
 const schema=`// Referência de apresentação derivada dos modelos do checkout.
// Recorte; no Android, esta área recebe o schema integral gerado.

message Backup {
  repeated BackupManga backupManga = 1;
  repeated BackupCategory backupCategories = 2;
  repeated BackupSource backupSources = 101;
  repeated BackupPreference backupPreferences = 104;
  repeated BackupSourcePreferences backupSourcePreferences = 105;
  repeated BackupExtensionStore backupExtensionStores = 106;
}

message BackupCategory {
  required string name = 1;
  optional int64 order = 2;
  optional int64 id = 3;
  optional int64 flags = 100;
}

message BackupSource {
  optional string name = 1;
  required int64 sourceId = 2;
}`;
 const fixtures=[{name:'Diagnóstico',page:'hub'},{name:'Logs — captura parada',page:'logs'},{name:'Logs — captura ativa',page:'logs',active:true},{name:'Limpar logs — confirmação',page:'logs',sheet:true},{name:'Informações técnicas',page:'technical'},{name:'Tarefas em segundo plano',page:'workers'},{name:'Esquema de backup',page:'schema'}];
 fixtures.forEach((fixture,index)=>{
  const phone=reference.cloneNode(true);phone.querySelectorAll('[id]').forEach(node=>node.removeAttribute('id'));phone.querySelectorAll('.dl-overlay').forEach(node=>node.remove());phone.dataset.themeEffective='light';
  const mount=phone.querySelector('.phone-content');mount.replaceChildren();
  const figure=document.createElement('figure');figure.setAttribute('aria-label',fixture.name);figure.append(phone);board.append(figure);
  const app=document.createElement('div');app.className='dl-app dg-app od-screen';mount.append(app);
  const live=document.createElement('div');live.className='dl-live';live.hidden=true;live.setAttribute('role','status');mount.append(live);
  const overlay=document.createElement('div');overlay.className='dl-overlay';overlay.hidden=true;phone.querySelector('.phone-screen').append(overlay);
  const state={page:fixture.page,active:!!fixture.active,record:{...technical},positions:{},history:[]};
  let origin=null,captureTimer=null,toastTimer=null;
  const titles={hub:'Diagnóstico',logs:'Logs',technical:'Informações técnicas',workers:'Tarefas em segundo plano',schema:'Esquema de backup'};
  const parents={hub:'Configurações',logs:'Diagnóstico',technical:'Diagnóstico',workers:'Informações técnicas',schema:'Informações técnicas'};
  function announce(message){clearTimeout(toastTimer);live.textContent=message;live.hidden=false;toastTimer=setTimeout(()=>live.hidden=true,5000)}
  function savePosition(){state.positions[state.page]=app.querySelector('.dl-scroll')?.scrollTop||0}
  function navigate(page){savePosition();state.history.push(state.page);state.page=page;render();app.querySelector('h1').focus({preventScroll:true})}
  function returnBack(){
   if(state.page==='hub'){announce('Retorno a Configurações; a referência anterior permanece preservada.');return}
   savePosition();state.page=state.history.pop()||(state.page==='workers'||state.page==='schema'?'technical':'hub');render();app.querySelector('h1').focus({preventScroll:true});
  }
  function workerText(list){return list.length?list.map(w=>'ID: '+w.id+'\nTags:\n'+w.tags.map(t=>'  '+t).join('\n')+'\nEstado: '+w.status+(w.next?'\nPróxima execução: '+w.next:'')+(w.attempt!==undefined?'\nTentativa: '+w.attempt:'')).join('\n\n'):'Nenhuma tarefa.'}
  function workerGroup(title,list){return section(title,list.length?list.map(w=>'<article class="dg-record"><dl><div><dt>ID</dt><dd>'+esc(w.id)+'</dd></div><div><dt>Tags</dt><dd><ul>'+w.tags.map(tag=>'<li>'+esc(tag)+'</li>').join('')+'</ul></dd></div><div><dt>Estado</dt><dd>'+esc(w.status)+'</dd></div>'+(w.next?'<div><dt>Próxima execução</dt><dd>'+esc(w.next)+'</dd></div>':'')+(w.attempt!==undefined?'<div><dt>Tentativa</dt><dd>'+esc(w.attempt)+'</dd></div>':'')+'</dl></article>').join(''):'<p class="dg-empty">Nenhuma tarefa '+(title==='Em execução'?'em execução':title==='Na fila'?'na fila':'concluída')+'.</p>')}
  function render(){
   const page=state.page;
   app.innerHTML='<header class="dl-header"><button type="button" class="dl-back" data-back>'+back+'<span>'+parents[page]+'</span></button><div class="dg-title-row"><h1 tabindex="-1">'+titles[page]+'</h1>'+(['workers','schema'].includes(page)?'<button type="button" class="dg-copy" data-copy aria-label="Copiar '+(page==='workers'?'as informações':'o esquema')+'">Copiar</button>':'')+'</div></header><div class="dl-scroll od-scroll"></div>';
   const scroll=app.querySelector('.dl-scroll');
   if(page==='hub')scroll.innerHTML='<div class="dl-group">'+row('logs','Logs','Capture e compartilhe informações para investigar problemas.','','',true)+row('technical','Informações técnicas','Versão, dispositivo e estado técnico do aplicativo.','','',true)+'</div>';
   if(page==='logs')scroll.innerHTML='<div class="dg-status" role="status"><strong>'+ (state.active?'Capturando logs':'Captura parada')+'</strong><p>'+(state.active?'A captura detalhada termina automaticamente após 15 minutos.':'Inicie uma captura antes de reproduzir o problema.')+'</p></div>'+section('Captura',state.active?row('stop','Parar logs','Encerra a captura atual e preserva esse período para o próximo relatório.','','dg-capture'):row('start','Iniciar logs','Inicia uma captura detalhada para ajudar a reproduzir um problema.','','dg-capture'))+section('Dados',row('clear','Limpar logs','Apaga os dados de diagnóstico armazenados pelo Tsuzuki.','','dg-destructive'))+section('Compartilhar',row('share','Compartilhar logs','Cria um relatório de diagnóstico para compartilhar.'))+'<p class="dl-note">'+privacy+'</p>';
   if(page==='technical'){
    const data=state.record;
    scroll.innerHTML=section('Aplicativo',info('Versão',data.version)+info('Data / build',data.build)+'<div class="dl-row od-row dg-id"><button type="button" class="dg-id-copy" data-action="id" aria-label="Copiar Installation ID">'+('<span class="dl-field"><span class="dl-label">Installation ID</span><span class="dl-value dg-id-value"><span>'+esc(data.installationId)+'</span>'+icon('M9 9h11v11H9zM5 15H4V4h11v1')+'</span></span>')+'</button><button type="button" class="dg-small od-fixed" data-action="regenerate" aria-label="Regenerar Installation ID">'+renew+'</button></div>'+info('Status de compilação',profileLabel(data))+info('Versão do WebView',data.webview))+section('Dispositivo',info('Modelo',data.model)+info('Versão do Android',data.android)+(data.oneUi?info('One UI',data.oneUi):data.miui?info('MIUI',data.miui):''))+section('Ferramentas',row('workers','Tarefas em segundo plano','Consulte o estado das tarefas agendadas pelo aplicativo.','','',true)+row('schema','Esquema de backup','Visualize o schema técnico usado pelos arquivos de backup.','','',true));
   }
   if(page==='workers')scroll.innerHTML=workerGroup('Em execução',workers.running)+workerGroup('Na fila',workers.enqueued)+workerGroup('Concluídas',workers.finished);
   if(page==='schema')scroll.innerHTML='<pre class="dg-schema" tabindex="0" aria-label="Esquema de backup, somente leitura"><code>'+esc(schema)+'</code></pre>';
   scroll.scrollTop=state.positions[page]||0;
   app.querySelector('[data-back]').onclick=returnBack;
   app.querySelectorAll('[data-action]').forEach(button=>button.onclick=()=>perform(button.dataset.action,button));
   const copyButton=app.querySelector('[data-copy]');if(copyButton)copyButton.onclick=()=>copy(page==='schema'?schema:'Em execução\n'+workerText(workers.running)+'\n\nNa fila\n'+workerText(workers.enqueued)+'\n\nConcluídas\n'+workerText(workers.finished),copyButton);
  }
  function perform(action,button){
   if(['logs','technical','workers','schema'].includes(action)){navigate(action);return}
   if(action==='clear'){openDialog('clear',button);return}
   if(action==='share'){announce('No Android: criar tsuzuki_logs.txt e abrir Compartilhar. Esta composição não acessa logs reais.');return}
   if(action==='id'){copy(state.record.installationId,button);return}
   if(action==='regenerate'){state.record.installationId=globalThis.crypto?.randomUUID?.()||'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,c=>{const r=Math.floor(Math.random()*16);return (c==='x'?r:(r&3)|8).toString(16)});savePosition();render();app.querySelector('[data-action="regenerate"]').focus({preventScroll:true});announce('Installation ID regenerado nesta demonstração local.');return}
   if(action==='start'||action==='stop'){
    savePosition();clearTimeout(captureTimer);state.active=action==='start';
    if(state.active)captureTimer=setTimeout(()=>{state.active=false;if(state.page==='logs'){savePosition();render()}announce('A captura demonstrativa terminou após 15 minutos.')},15*60*1000);
    render();app.querySelector('[data-action="'+(state.active?'stop':'start')+'"]').focus({preventScroll:true});
   }
  }
  async function copy(text,button){try{if(!navigator.clipboard?.writeText)throw new Error('clipboard unavailable');await navigator.clipboard.writeText(text);announce('Copiado.')}catch{openDialog('copy',button,text)}}
  function dismiss(){overlay.hidden=true;overlay.replaceChildren();app.inert=false;if(origin?.isConnected)origin.focus({preventScroll:true})}
  function openDialog(kind,button,text=''){
   origin=button||app.querySelector('[data-action="clear"]');app.inert=true;overlay.hidden=false;
   const id='dg-dialog-'+index;
   overlay.innerHTML='<section class="dl-sheet" role="dialog" aria-modal="true" aria-labelledby="'+id+'" aria-describedby="'+id+'-body"><header class="dl-sheet-head"><div class="dl-sheet-heading"><h2 id="'+id+'">'+(kind==='clear'?'Limpar logs?':'Copiar informações')+'</h2><button type="button" class="dl-close" aria-label="Fechar">'+close+'</button></div></header><div class="dl-options"><p id="'+id+'-body">'+(kind==='clear'?clearCopy:'Selecione e copie o texto abaixo.')+'</p>'+(kind==='copy'?'<textarea class="dg-manual-copy" readonly aria-label="Informações para copiar">'+esc(text)+'</textarea>':'')+'</div><footer class="dl-sheet-footer"><button type="button" class="dl-action" data-cancel>'+ (kind==='clear'?'Cancelar':'Fechar')+'</button>'+(kind==='clear'?'<button type="button" class="dl-action dl-primary dg-clear" data-confirm>Limpar</button>':'')+'</footer></section>';
   overlay.querySelector('.dl-close').onclick=dismiss;overlay.querySelector('[data-cancel]').onclick=dismiss;
   const confirm=overlay.querySelector('[data-confirm]');if(confirm)confirm.onclick=()=>{clearTimeout(captureTimer);state.active=false;savePosition();dismiss();render();app.querySelector('[data-action="clear"]').focus({preventScroll:true});announce('Demonstração: diagnósticos e último crash salvo apagados. Nenhum arquivo real foi alterado.')};
   overlay.querySelector('[data-cancel]').focus({preventScroll:true});
   const area=overlay.querySelector('textarea');if(area){area.focus({preventScroll:true});area.select()}
  }
  overlay.onclick=event=>{if(event.target===overlay)dismiss()};
  phone.addEventListener('keydown',event=>{if(overlay.hidden)return;if(event.key==='Escape'){event.preventDefault();dismiss()}if(event.key==='Tab'){const controls=[...overlay.querySelectorAll('button,textarea')],first=controls[0],last=controls.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}}});
  render();if(state.active)captureTimer=setTimeout(()=>{state.active=false;if(state.page==='logs'){savePosition();render()}},15*60*1000);if(fixture.sheet)openDialog('clear');
 });
 document.body.className='diagnostic-comparison';document.body.append(board);document.title='Diagnóstico · Tsuzuki';
})();
