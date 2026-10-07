// Resolve the effective theme before first paint (no flash): saved choice, else system preference.
(function () {
    var t;
    try { t = localStorage.getItem('theme'); } catch (e) { /* private mode */ }
    if (t !== 'dark' && t !== 'light' && t !== 'jetbrains' && t !== 'terminal')
        t = matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', t);
})();
