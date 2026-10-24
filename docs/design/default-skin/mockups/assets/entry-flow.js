const ENTRY_SHELL="\n  <div class=\"phone-frame\" data-phone-shell data-platform=\"iphone\">\n    <span class=\"hardware-button silent\" aria-hidden=\"true\"></span>\n    <span class=\"hardware-button volume-up\" aria-hidden=\"true\"></span>\n    <span class=\"hardware-button volume-down\" aria-hidden=\"true\"></span>\n    <span class=\"hardware-button power\" aria-hidden=\"true\"></span>\n\n    <section class=\"phone-screen\" data-phone-screen>\n      <main class=\"phone-content\" data-phone-content>\n        <!-- APP CONTENT START --><!-- APP CONTENT END -->\n      </main>\n\n      <div class=\"status-bar\" aria-hidden=\"true\">\n        <span>9:41</span>\n        <span class=\"status-icons\">\n          <svg viewBox=\"0 0 18 12\"><path d=\"M1 10.5h2V8H1v2.5Zm4 0h2V5.5H5v5Zm4 0h2V3H9v7.5Zm4 0h2V.5h-2v10Z\"/></svg>\n          <svg viewBox=\"0 0 18 12\"><path d=\"M9 11.2 7.2 9.4a2.55 2.55 0 0 1 3.6 0L9 11.2Zm4.1-4.1-1.35 1.35a3.9 3.9 0 0 0-5.5 0L4.9 7.1a5.8 5.8 0 0 1 8.2 0Zm3-3-1.35 1.35a8.15 8.15 0 0 0-11.5 0L1.9 4.1a10.05 10.05 0 0 1 14.2 0Z\"/></svg>\n          <svg class=\"battery\" viewBox=\"0 0 27 12\"><path fill-rule=\"evenodd\" d=\"M2.5 1h19A2.5 2.5 0 0 1 24 3.5v5a2.5 2.5 0 0 1-2.5 2.5h-19A2.5 2.5 0 0 1 0 8.5v-5A2.5 2.5 0 0 1 2.5 1Zm0 1A1.5 1.5 0 0 0 1 3.5v5A1.5 1.5 0 0 0 2.5 10h19A1.5 1.5 0 0 0 23 8.5v-5A1.5 1.5 0 0 0 21.5 2h-19Zm.5 1h17.5A1.5 1.5 0 0 1 22 4.5v3A1.5 1.5 0 0 1 20.5 9H3V3Zm22 1.5a1.5 1.5 0 0 1 0 3v-3Z\"/></svg>\n        </span>\n      </div>\n      <div class=\"dynamic-island\" aria-hidden=\"true\"></div>\n      <div class=\"home-indicator\" aria-hidden=\"true\"></div>\n    </section>\n  </div>\n";
(() => {
  'use strict';
  const board = document.createElement('section');
  board.id = 'entry-flow-board';
  board.innerHTML = '<header class="ef-board-heading"><h1>Primeiro acesso · Tsuzuki</h1><p>Welcome e autenticação · revisão visual. Operações demonstrativas, sem conexão com serviços de conta.</p><button class="ef-reference" type="button">Ver Home e boards anteriores</button></header><div class="ef-frames"></div>';
  const previousClass = document.body.className;
  document.body.append(board);
  document.body.className = 'entry-flow-entry';
  document.title = 'Primeiro acesso · Tsuzuki Default Skin';
  const goHome = () => {
    try { sessionStorage.setItem('tsuzuki:entry-demo:completed', 'true'); } catch (_) {}
    document.body.className = previousClass;
    const home = document.getElementById('home-board');
    home?.scrollIntoView({ block: 'start' });
    if (home && !home.querySelector('[data-entry-review]')) {
      const returnButton = document.createElement('button');
      returnButton.className = 'hm-board-action';
      returnButton.dataset.entryReview = '';
      returnButton.textContent = 'Voltar à revisão de primeiro acesso';
      returnButton.onclick = () => { document.body.className = 'entry-flow-entry'; board.scrollIntoView(); };
      home.querySelector('header').append(returnButton);
    }
  };
  board.querySelector('.ef-reference').onclick = goHome;
  const back = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 5-7 7 7 7M7 12h14"/></svg>';
  const eye = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></svg>';
  const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const frames = [['welcome','First install — Welcome'],['login','Entrar'],['signup','Criar conta'],['recovery','Esqueci minha senha'],['reset','Redefinir senha']];
  frames.forEach(([initial, label], index) => {
    const frame = document.createElement('article');
    frame.className = 'ef-frame';
    frame.innerHTML = '<h2 class="ef-caption">'+label+'</h2>'+ENTRY_SHELL+'<p class="ef-note">'+(initial === 'welcome' ? 'Primeira instalação apenas. Visitante segue diretamente para Home.' : 'Mesma família visual de Conta. Formulário e respostas locais.')+'</p>';
    board.querySelector('.ef-frames').append(frame);
    const content = frame.querySelector('.phone-content');
    let route = initial, recoveryOrigin = 'login', email = '';
    const id = key => 'ef-'+index+'-'+key;
    const navigate = next => { route = next; render(); content.scrollTop = 0; content.querySelector('h1')?.focus({preventScroll:true}); };
    function field(key, title, type, autocomplete) {
      return '<div class="ef-field od-field"><label for="'+id(key)+'">'+title+' <span class="ef-required">(obrigatório)</span></label><div class="ef-input-wrap"><input id="'+id(key)+'" name="'+key+'" type="'+type+'" autocomplete="'+autocomplete+'" required aria-describedby="'+id(key+'-error')+'" '+(key === 'email' ? 'inputmode="email" autocapitalize="none" spellcheck="false" value="'+esc(email)+'"' : '')+'>'+(key === 'password' ? '<button class="ef-reveal" type="button" aria-label="Mostrar senha" aria-pressed="false">'+eye+'</button>' : '')+'</div><p class="ef-error" id="'+id(key+'-error')+'" hidden></p></div>';
    }
    function render() {
      if (route === 'welcome') {
        content.innerHTML = '<section class="ef-welcome"><div class="ef-welcome-inner"><h1 class="ef-brand" tabindex="-1"><img src="assets/sobre/tsuzuki-lockup.svg" width="294" height="64" alt="Tsuzuki"></h1><p class="ef-support">Seu espaço para ler, organizar e acompanhar suas obras.</p><div class="ef-welcome-actions od-stack"><button class="ef-primary" data-route="signup">Criar conta</button><button class="ef-secondary" data-route="login">Entrar</button><button class="ef-text ef-guest" data-guest>Continuar como visitante</button></div></div></section>';
        content.querySelector('[data-guest]').onclick = goHome;
      } else {
        const title = {login:'Entrar',signup:'Criar conta',recovery:'Esqueci minha senha',confirmation:'Confira seu email',recovered:'Confira seu email',reset:'Redefinir senha',resetDone:'Senha redefinida'}[route];
        content.innerHTML = '<section class="ef-auth"><button class="ef-back" aria-label="Voltar">'+back+'</button><h1 tabindex="-1">'+title+'</h1></section>';
        const auth = content.querySelector('.ef-auth');
        content.querySelector('.ef-back').onclick = () => navigate(route === 'reset' || route === 'resetDone' ? 'login' : route === 'recovery' || route === 'recovered' ? recoveryOrigin : route === 'confirmation' ? 'signup' : 'welcome');
        if (route === 'resetDone') {
          auth.insertAdjacentHTML('beforeend','<p class="ef-lead" role="status">Sua nova senha foi salva. Entre para continuar.</p><button class="ef-primary" data-route="login">Entrar</button>');
        } else if (route === 'reset') {
          auth.insertAdjacentHTML('beforeend','<p class="ef-lead">Crie uma nova senha para sua conta.</p><form class="ef-form od-stack" novalidate><div class="ef-summary" role="alert" hidden></div>'+field('password','Nova senha','password','new-password')+field('confirmation','Confirmar nova senha','password','new-password')+'<button class="ef-primary ef-submit" type="submit">Salvar nova senha</button><p class="ef-feedback" role="status" aria-live="polite" hidden></p></form>');
          const form = content.querySelector('form');
          const password = form.querySelector('[name=password]');
          const confirmation = form.querySelector('[name=confirmation]');
          const validate = input => {
            const error = !input.value ? (input === password ? 'Informe sua nova senha.' : 'Confirme sua nova senha.') : input === confirmation && input.value !== password.value ? 'As senhas não coincidem. Digite a mesma senha nos dois campos.' : '';
            input.setAttribute('aria-invalid',String(Boolean(error)));
            const message = content.querySelector('#'+id(input.name+'-error'));
            message.textContent = error; message.hidden = !error;
            return !error;
          };
          [password,confirmation].forEach(input => input.addEventListener('blur',()=>{validate(input);if(input === password && confirmation.value)validate(confirmation);}));
          form.querySelector('.ef-reveal').onclick = e => {
            const shown = password.type === 'password'; password.type = shown ? 'text' : 'password';
            e.currentTarget.setAttribute('aria-pressed',String(shown));
            e.currentTarget.setAttribute('aria-label',shown ? 'Ocultar nova senha' : 'Mostrar nova senha');
          };
          form.onsubmit = e => {
            e.preventDefault();
            const invalid = [password,confirmation].filter(input => !validate(input));
            const summary = form.querySelector('.ef-summary'); summary.hidden = !invalid.length;
            if(invalid.length){
              summary.innerHTML = 'Revise os campos indicados: '+invalid.map(input=>'<a href="#'+input.id+'">'+(input === password ? 'Nova senha' : 'Confirmar nova senha')+'</a>').join(' · ');
              summary.querySelectorAll('a').forEach(link=>link.onclick=event=>{event.preventDefault();content.querySelector(link.getAttribute('href')).focus();});
              invalid[0].focus(); return;
            }
            const submit = form.querySelector('.ef-submit'); submit.disabled = true; submit.textContent = 'Salvando…'; form.setAttribute('aria-busy','true');
            const feedback = form.querySelector('.ef-feedback'); feedback.hidden = false; feedback.textContent = 'Salvando nova senha…';
            setTimeout(()=>{if(!form.isConnected || route !== 'reset')return;form.reset();navigate('resetDone');},600);
          };
        } else if (route === 'confirmation' || route === 'recovered') {
          auth.insertAdjacentHTML('beforeend','<p class="ef-lead" role="status">'+(route === 'confirmation' ? 'Confirme seu email para concluir o cadastro.' : 'Se houver uma conta com este email, você receberá as instruções de recuperação.')+'</p><p class="ef-email">'+esc(email)+'</p><button class="ef-primary" data-route="login">Voltar para Entrar</button>');
        } else {
          const isRecovery = route === 'recovery';
          auth.insertAdjacentHTML('beforeend','<p class="ef-lead">'+(route === 'login' ? 'Entre na sua conta Tsuzuki.' : route === 'signup' ? 'Crie sua conta para começar.' : 'Informe seu email para receber as instruções de recuperação.')+'</p><form class="ef-form od-stack" novalidate><div class="ef-summary" role="alert" hidden></div>'+field('email','Email','email','email')+(!isRecovery ? field('password','Senha','password',route === 'signup' ? 'new-password' : 'current-password') : '')+(route === 'login' ? '<button class="ef-text ef-forgot" type="button" data-route="recovery">Esqueci minha senha</button>' : '')+'<button class="ef-primary ef-submit" type="submit">'+(isRecovery ? 'Enviar instruções' : title)+'</button><p class="ef-feedback" role="status" aria-live="polite" hidden></p></form>'+(!isRecovery ? '<div class="ef-cross"><p>'+(route === 'login' ? 'Ainda não tem uma conta?' : 'Já tem uma conta?')+'</p><button class="ef-text" data-route="'+(route === 'login' ? 'signup' : 'login')+'">'+(route === 'login' ? 'Criar conta' : 'Entrar')+'</button></div>' : ''));
          const form = content.querySelector('form');
          const validate = input => {
            const error = !input.value.trim() ? (input.name === 'email' ? 'Informe seu email.' : 'Informe sua senha.') : input.type === 'email' && !input.validity.valid ? 'Use um email completo, como nome@exemplo.com.' : '';
            input.setAttribute('aria-invalid', String(Boolean(error)));
            const message = content.querySelector('#'+id(input.name+'-error'));
            message.textContent = error; message.hidden = !error; return !error;
          };
          form.querySelectorAll('input').forEach(input => input.addEventListener('blur', () => { if (input.name === 'email') email = input.value.trim(); validate(input); }));
          content.querySelector('.ef-reveal')?.addEventListener('click', e => {
            const button = e.currentTarget, input = content.querySelector('[name=password]');
            const shown = input.type === 'password'; input.type = shown ? 'text' : 'password';
            button.setAttribute('aria-pressed', String(shown)); button.setAttribute('aria-label', shown ? 'Ocultar senha' : 'Mostrar senha');
          });
          form.onsubmit = e => {
            e.preventDefault();
            const inputs = [...form.querySelectorAll('input')], invalid = inputs.filter(input => !validate(input));
            const summary = form.querySelector('.ef-summary');
            summary.hidden = !invalid.length;
            if (invalid.length) {
              summary.innerHTML = 'Revise os campos indicados: '+invalid.map(input => '<a href="#'+input.id+'">'+(input.name === 'email' ? 'Email' : 'Senha')+'</a>').join(' · ');
              summary.querySelectorAll('a').forEach(link => link.onclick = event => { event.preventDefault(); content.querySelector(link.getAttribute('href')).focus(); });
              invalid[0].focus(); return;
            }
            email = form.querySelector('[name=email]').value.trim();
            const submit = form.querySelector('.ef-submit'), feedback = form.querySelector('.ef-feedback'), submittedRoute = route;
            submit.disabled = true; submit.textContent = 'Aguarde…'; form.setAttribute('aria-busy','true');
            feedback.hidden = false; feedback.textContent = 'Processando…';
            setTimeout(() => {
              if (!form.isConnected || route !== submittedRoute) return;
              form.querySelector('[name=password]')?.setAttribute('value','');
              if (submittedRoute === 'login') { form.reset(); goHome(); }
              else navigate(submittedRoute === 'signup' ? 'confirmation' : 'recovered');
            }, 600);
          };
        }
      }
      content.querySelectorAll('[data-route]').forEach(button => button.onclick = () => {
        const input = content.querySelector('[name=email]'); if (input) email = input.value.trim();
        if (button.dataset.route === 'recovery') recoveryOrigin = route;
        navigate(button.dataset.route);
      });
    }
    render();
  });
})();
