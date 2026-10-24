// Robot API token for token-gated paths (ROBOT_API_TOKENS on the server).
// If the server runs with tokens set, every page including this file prompts
// once for the token, keeps it in localStorage, and attaches it to all
// fetch() calls automatically. With an empty server token list this is a
// no-op header-wise.
(function () {
  var t = localStorage.getItem('robot_token');
  if (t === null) {
    t = prompt('Robot API token (để trống nếu server không bật token):') || '';
    localStorage.setItem('robot_token', t);
  }
  var _fetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    init = init || {};
    if (t) {
      init.headers = Object.assign({ 'Authorization': 'Bearer ' + t },
                                   init.headers || {});
    }
    return _fetch(input, init);
  };
})();
