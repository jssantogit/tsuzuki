/* Five design fixtures. Native Android SAF, validation and jobs are not executed. */
(function(){
 const reference=document.querySelector('.downloads-board .phone-frame');
 if(!reference)return;
 const board=document.createElement('section');board.className='storage-board';board.setAttribute('aria-label','Dados e armazenamento');
 const icon=path=>'<svg class="dl-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="'+path+'"/></svg>';
 const back=icon('m15 18-6-6 6-6M9 12h12'),chevron=icon('m9 6 6 6-6 6'),close=icon('m6 6 12 12M18 6 6 18'),check=icon('m5 12 4 4L19 6');
 const esc=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const labels={root:'Dados e armazenamento',usage:'Uso do armazenamento',backup:'Backup e restauração',export:'Exportar biblioteca',location:'Local de armazenamento',create:'Criar backup',restore:'Restaurar backup',missing:'Restaurar backup'};
 const libraryOptions=[['libraryEntries','Mangás'],['chapters','Capítulos'],['tracking','Tracking'],['history','Histórico'],['categories','Categorias'],['readEntries','Dados de leitura fora da biblioteca']];
 const settingsOptions=[['appSettings','Configurações do app'],['extensionStores','Repositórios de extensões'],['sourceSettings','Configurações de fontes'],['privateSettings','Configurações privadas']];
 const restoreOptions=[['libraryEntries','Biblioteca'],['categories','Categorias'],['appSettings','Configurações do app'],['extensionStores','Repositórios de extensões'],['sourceSettings','Configurações de fontes']];
 const dependentLibrary=new Set(['chapters','tracking','history','readEntries']);
 const frequencies=[['off','Desativado'],['6','A cada 6 horas'],['12','A cada 12 horas'],['24','A cada 24 horas'],['48','A cada 48 horas'],['168','Semanal']];
 const field=(label,value='',help='')=>'<span class="dl-field od-field od-fill"><span class="dl-label">'+esc(label)+'</span>'+(value?'<span class="dl-value">'+esc(value)+'</span>':'')+(help?'<span class="dl-help">'+esc(help)+'</span>':'')+'</span>';
 const section=(label,content)=>'<section class="dl-section"><h2>'+esc(label)+'</h2><div class="dl-group">'+content+'</div></section>';
 ['location','create','restore','missing','export'].forEach((initial,index)=>{
  const figure=document.createElement('figure');figure.setAttribute('aria-label',labels[initial]);
  const phone=reference.cloneNode(true);phone.querySelectorAll('[id]').forEach(node=>node.removeAttribute('id'));
  phone.querySelectorAll('.dl-overlay').forEach(node=>node.remove());phone.dataset.themeEffective='light';
  const mount=phone.querySelector('.phone-content');mount.replaceChildren();figure.append(phone);board.append(figure);
  const app=document.createElement('div');app.className='dl-app ds-app od-screen';mount.append(app);
  const live=document.createElement('div');live.className='dl-live';live.setAttribute('role','status');live.hidden=true;mount.append(live);
  const overlay=document.createElement('div');overlay.className='dl-overlay';overlay.hidden=true;phone.querySelector('.phone-screen').append(overlay);
  const state={autoCache:true,frequency:'24',title:true,author:true,artist:true,path:'Armazenamento interno › Tsuzuki',folderName:'Pasta do Tsuzuki',restoreStatus:initial==='missing'?'missing':'valid',missingSources:['MangaFire'],missingTracking:['Kitsu'],selectedFile:'Backup da biblioteca',create:Object.fromEntries([...libraryOptions,...settingsOptions].map(([key])=>[key,key!=='privateSettings'])),restore:Object.fromEntries(restoreOptions.map(([key])=>[key,true]))};
  let mode=initial,positions={},timer,opener;
  const row=(action,label,value)=>'<button type="button" class="dl-row od-row" data-action="'+action+'">'+field(label,value)+chevron+'</button>';
  const operational=(action,label,value,help='')=>'<div class="dl-row od-row">'+field(label,value,help)+'<button type="button" class="ds-operation od-fixed" data-action="'+action+'">Limpar</button></div>';
  const toggle=(key,label)=>'<div class="dl-row od-row">'+field(label)+'<button type="button" class="dl-switch od-fixed" role="switch" aria-label="'+esc(label)+'" aria-checked="'+state[key]+'" data-toggle="'+key+'"></button></div>';
  const frequency=()=>frequencies.find(entry=>entry[0]===state.frequency)[1];
  const canCreate=()=>['libraryEntries','categories','appSettings','extensionStores','sourceSettings'].some(key=>state.create[key]);
  const canRestore=()=>state.restoreStatus!=='invalid'&&restoreOptions.some(([key])=>state.restore[key]);
  const isDisabled=key=>dependentLibrary.has(key)?!state.create.libraryEntries:key==='privateSettings'?!(state.create.appSettings||state.create.sourceSettings):false;
  const choices=(options,scope)=>options.map(([key,label])=>'<label class="ds-choice" data-disabled="'+(scope==='create'&&isDisabled(key))+'"><input type="checkbox" data-option="'+key+'" data-scope="'+scope+'" '+(state[scope][key]?'checked ':'')+(scope==='create'&&isDisabled(key)?'disabled ':'')+'><span>'+esc(label)+'</span></label>').join('');
  function announce(message){clearTimeout(timer);live.textContent=message;live.hidden=false;timer=setTimeout(()=>live.hidden=true,5000)}
  function render(next=mode,focus=false){
   const prior=app.querySelector('.dl-scroll');if(prior)positions[mode]=prior.scrollTop;mode=next;
   const parent=(mode==='create'||mode==='restore'||mode==='missing')?'Backup e restauração':mode==='root'?'Configurações':'Dados e armazenamento';
   const cta=mode==='export'?['export','Exportar CSV',true]:mode==='create'?['save-backup','Criar backup',canCreate()]:(mode==='restore'||mode==='missing')?['start-restore','Restaurar backup',canRestore()]:null;
   app.innerHTML='<header class="dl-header"><button type="button" class="dl-back" data-back>'+back+'<span>'+parent+'</span></button><h1 tabindex="-1">'+labels[mode]+'</h1></header><div class="dl-scroll od-scroll"></div>'+(cta?'<footer class="ds-footer"><button type="button" class="dl-action dl-primary" data-action="'+cta[0]+'" '+(cta[2]?'':'disabled')+'>'+cta[1]+'</button></footer>':'');
   const scroll=app.querySelector('.dl-scroll');
   if(mode==='root')scroll.innerHTML=section('Armazenamento',row('folder','Local de armazenamento',state.path)+row('usage','Uso do armazenamento','Espaço, cache e arquivos temporários'))+section('Backup',row('backup','Backup e restauração',state.frequency==='off'?'Automático desativado':state.frequency==='168'?'Automático semanal':'Automático a cada '+state.frequency+' horas'))+section('Exportação',row('export-page','Exportar biblioteca','CSV'));
   if(mode==='usage')scroll.innerHTML=section('Dispositivo','<div class="ds-volume"><span class="dl-label">Armazenamento interno</span><p class="ds-path">Tsuzuki</p><progress class="ds-meter" max="128" value="54" aria-label="54 GB usados de 128 GB"></progress><span class="ds-space">74 GB disponíveis de 128 GB</span></div>')+section('Cache',operational('cache','Cache de capítulos','84 MB')+toggle('autoCache','Limpar cache automaticamente'))+section('Arquivos temporários',operational('temporary','Arquivos temporários','','Arquivos gerenciados pelo Tsuzuki durante aquisições temporárias.'));
   if(mode==='backup')scroll.innerHTML=section('Manual',row('create-backup','Criar backup','Escolha os dados e salve um arquivo de backup')+row('restore-backup','Restaurar backup','Selecione um arquivo de backup existente'))+section('Backup automático',row('frequency','Frequência',frequency())+'<div class="dl-row od-row">'+field('Último backup automático','Há 2 horas')+'</div>');
   if(mode==='export')scroll.innerHTML='<p class="ds-intro">Crie um arquivo CSV com informações da sua biblioteca.</p>'+section('Campos',[['title','Título'],['author','Autor'],['artist','Artista']].map(([key,label])=>'<label class="ds-choice"><input type="checkbox" data-field="'+key+'" '+(state[key]?'checked ':'')+(key!=='title'&&!state.title?'disabled ':'')+'><span>'+label+'</span></label>').join(''))+(!state.title?'<p class="ds-note">Selecione Título para incluir Autor e Artista.</p>':'');
   if(mode==='location')scroll.innerHTML=section('Local atual','<div class="ds-location"><span class="dl-label ds-location-name">'+esc(state.path?state.folderName:'Nenhuma pasta selecionada')+'</span><p class="ds-path">'+esc(state.path||'Escolha uma pasta válida para armazenar os arquivos do Tsuzuki.')+'</p><button type="button" class="ds-location-action" data-action="choose-folder">'+(state.path?'Alterar local':'Escolher pasta')+'</button></div>');
   if(mode==='create')scroll.innerHTML=section('Biblioteca',choices(libraryOptions,'create'))+(!state.create.libraryEntries?'<p class="ds-note">Selecione Mangás para incluir capítulos, tracking, histórico e leitura fora da biblioteca.</p>':'')+section('Configurações',choices(settingsOptions,'create'))+(!(state.create.appSettings||state.create.sourceSettings)?'<p class="ds-note">Configurações privadas depende das configurações do app ou de fontes.</p>':'');
   if(mode==='restore'||mode==='missing'){
    const invalid=state.restoreStatus==='invalid';
    const missingGroups=[['Fontes',state.missingSources],['Tracking',state.missingTracking]].filter(([,names])=>names.length);
    const warning=state.restoreStatus==='missing'&&missingGroups.length?'<section class="ds-notice" aria-labelledby="ds-missing-'+index+'"><h2 id="ds-missing-'+index+'">Componentes ausentes</h2><p>Alguns componentes deste backup não estão disponíveis. Você ainda pode restaurar os dados.</p><dl>'+missingGroups.map(([label,names])=>'<div><dt>'+esc(label)+'</dt><dd><ul class="ds-missing-list">'+names.map(name=>'<li>'+esc(name)+'</li>').join('')+'</ul></dd></div>').join('')+'</dl></section>':'';
    scroll.innerHTML=invalid?'<section class="ds-notice ds-invalid" role="alert"><h2>Arquivo inválido</h2><p>Não foi possível validar este arquivo de backup. Selecione outro arquivo para continuar.</p><button type="button" class="ds-location-action" data-action="restore-backup">Escolher outro arquivo</button></section>':'<p class="ds-file">'+esc(state.selectedFile)+'</p>'+warning+section('Restaurar',choices(restoreOptions,'restore'));
   }
   scroll.scrollTop=positions[mode]||0;
   app.querySelector('[data-back]').onclick=()=>{if(mode==='root')announce('Retorno a Configurações, referência preservada nesta composição.');else render(mode==='create'||mode==='restore'||mode==='missing'?'backup':'root',true)};
   app.querySelectorAll('[data-toggle]').forEach(button=>button.onclick=()=>{const key=button.dataset.toggle;state[key]=!state[key];button.setAttribute('aria-checked',state[key])});
   app.querySelectorAll('[data-field]').forEach(input=>input.onchange=()=>{const key=input.dataset.field;state[key]=input.checked;if(key==='title'){if(!state.title){state.author=false;state.artist=false}render();app.querySelector('[data-field="title"]').focus({preventScroll:true})}});
   app.querySelectorAll('[data-option]').forEach(input=>input.onchange=()=>{const key=input.dataset.option,scope=input.dataset.scope;state[scope][key]=input.checked;render();app.querySelector('[data-option="'+key+'"]').focus({preventScroll:true})});
   app.querySelectorAll('[data-action]').forEach(button=>button.onclick=()=>handle(button.dataset.action,button));
   if(focus)app.querySelector('h1').focus({preventScroll:true});
  }
  async function handle(action,button){
   if(action==='usage'||action==='backup'){render(action,true);return}
   if(action==='export-page'){render('export',true);return}
   if(action==='frequency'){openFrequency(button);return}
   if(action==='folder'){render('location',true);return}
   if(action==='create-backup'){render('create',true);return}
   if(action==='restore-backup'){
    const input=document.createElement('input');input.type='file';input.accept='.tachibk,.proto,.gz,application/*';input.hidden=true;mount.append(input);
    input.onchange=()=>{const file=input.files[0];if(file){state.selectedFile=file.name;state.restoreStatus='invalid';render('restore',true);announce('Arquivo escolhido. Este protótipo não executa BackupFileValidator; a restauração está bloqueada.')}input.remove()};
    input.addEventListener('cancel',()=>input.remove(),{once:true});input.click();return;
   }
   if(action==='choose-folder'){
    if(!window.showDirectoryPicker){announce('Na implementação Android, esta ação abre o seletor de pasta do sistema.');return}
    try{const directory=await window.showDirectoryPicker({mode:'readwrite'});state.path=directory.name;state.folderName=directory.name;render();announce('Pasta escolhida nesta demonstração. Nenhum arquivo foi alterado.')}catch(error){if(error.name!=='AbortError')announce('O seletor de pasta do sistema não está disponível neste ambiente.')}
    return;
   }
   const messages={
    cache:'Demonstração: a implementação limpará o cache de capítulos. Nenhum arquivo foi removido.',
    temporary:'Demonstração: a implementação abrirá a confirmação para limpar arquivos temporários.',
    'save-backup':'Seleção pronta. No Android, o sistema abre o seletor para salvar o backup; nenhum arquivo foi criado nesta demonstração.',
    'start-restore':'Seleção pronta. No Android, a restauração validada inicia BackupRestoreJob; nenhum dado foi alterado nesta demonstração.',
    export:'Destino: criação de documento CSV pelo sistema. Nenhuma biblioteca real foi exportada.'
   };
   announce(messages[action]);
  }
  function dismiss(){overlay.hidden=true;overlay.replaceChildren();app.inert=false;opener?.focus({preventScroll:true})}
  function openFrequency(origin){
   opener=origin;app.inert=true;overlay.hidden=false;
   overlay.innerHTML='<section class="dl-sheet" role="dialog" aria-modal="true" aria-labelledby="ds-frequency-'+index+'"><header class="dl-sheet-head"><div class="dl-sheet-heading"><h2 id="ds-frequency-'+index+'">Frequência</h2><button type="button" class="dl-close" aria-label="Fechar">'+close+'</button></div></header><div class="dl-options" role="radiogroup" aria-label="Frequência do backup">'+frequencies.map(([value,label])=>'<button type="button" class="dl-option" role="radio" aria-checked="'+(value===state.frequency)+'" data-frequency="'+value+'"><span>'+label+'</span><span class="dl-state">'+(value===state.frequency?check:'')+'</span></button>').join('')+'</div></section>';
   overlay.querySelector('.dl-close').onclick=dismiss;
   overlay.querySelectorAll('[data-frequency]').forEach(button=>button.onclick=()=>{state.frequency=button.dataset.frequency;dismiss();render();app.querySelector('[data-action="frequency"]').focus({preventScroll:true})});
   overlay.querySelector('.dl-close').focus({preventScroll:true});
  }
  overlay.onclick=event=>{if(event.target===overlay)dismiss()};
  phone.addEventListener('keydown',event=>{
   if(overlay.hidden)return;
   if(event.key==='Escape'){event.preventDefault();dismiss();return}
   if(event.key==='Tab'){const controls=[...overlay.querySelectorAll('button:not(:disabled)')];const first=controls[0],last=controls.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}}
   if(event.key==='ArrowDown'||event.key==='ArrowUp'){const radios=[...overlay.querySelectorAll('[role="radio"]')],at=radios.indexOf(document.activeElement);if(at>=0){event.preventDefault();radios[(at+(event.key==='ArrowDown'?1:-1)+radios.length)%radios.length].focus()}}
  });
  render();
 });
 document.body.className='storage-comparison';document.body.append(board);document.title='Dados e armazenamento · Tsuzuki';
})();
