<template>
  <main class="home-page">
    <header class="home-nav">
      <RouterLink to="/" class="home-nav__brand">NovelPlayer</RouterLink>

      <nav class="home-nav__links" aria-label="首页导航">
        <RouterLink to="/workspace">工作台</RouterLink>
        <a href="#product">产品能力</a>
        <a href="#contact">联系我们</a>
      </nav>
    </header>

    <section class="home-stage">
      <p class="home-announcement">
        <span>🎉</span>
        项目已上线~当前可直接进入改编工作台体验完整流程。
      </p>

      <h1 class="home-wordmark">novelplayer</h1>
      <p class="home-slogan">把小说原文，推进成可以继续打磨的剧本工作流</p>

      <div class="home-entry-grid" id="product">
        <RouterLink to="/workspace" class="home-entry-card home-entry-card--primary">
          <strong>开始改编</strong>
          <span>进入工作台，从章节识别、生成日志到 YAML 初稿一口气走完。</span>
        </RouterLink>

        <article class="home-entry-card">
          <strong>模板中心</strong>
          <span>后续会接入角色模板、改编模板和团队协作入口，现在先预留在首页。</span>
        </article>
      </div>
    </section>

    <footer class="home-footer" id="contact">
      <section>
        <h2>产品</h2>
        <a href="#product">小说转剧本</a>
        <a href="#product">YAML 草稿编辑</a>
        <RouterLink to="/workspace">工作台入口</RouterLink>
      </section>

      <section>
        <h2>流程</h2>
        <a href="#product">输入原文</a>
        <a href="#product">识别章节</a>
        <a href="#product">生成剧本</a>
      </section>

      <section>
        <h2>项目</h2>
        <a href="#contact">NovelPlayer</a>
        <a href="#contact">前端重构中</a>
        <a href="#contact">更多页面待接入</a>
      </section>
    </footer>
  </main>
</template>
