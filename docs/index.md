---
layout: home

hero:
  name: "DivineMC"
  tagline: "DivineMC is the fork of Purpur compatible with Spigot plugins, offering the best performance for your server."
  image:
    src: logo.png
    alt: DivineMC
  actions:
    - theme: brand
      text: Download
      link: https://github.com/DivineMC/DivineMC/releases/latest
    - theme: alt
      text: Documentation
      link: /docs/

features:
  - icon: 🍴
    title: Fork of Purpur
    details: DivineMC is the fork of Purpur compatible with Spigot plugins, offering the best performance for your server.
  - icon: 📰
    title: Lithium patches
    details: DivineMC contains Lithium patches that optimizing many areas in game.
  - icon: 🆕
    title: New features
    details: DivineMC contains some PaperMC pull requests patches.
  - icon: 🐛
    title: Bug fixes
    details: DivineMC have patches that fixes bugs for several Minecraft issues.
  - icon: 🔌
    title: Plugin compatibility
    details: DivineMC have full compatibility with Spigot & Paper plugins.
  - icon: 🔃
    title: Always up-to date
    details: DivineMC always updated to latest version of Purpur
---

<style>
:root {
  --vp-home-hero-name-color: transparent;
  --vp-home-hero-name-background: -webkit-linear-gradient(120deg, #ffa38a 30%, #310085);

  --vp-home-hero-image-background-image: linear-gradient(-45deg, #ffa38a 50%, #310085 50%);
  --vp-home-hero-image-filter: blur(40px);
}

@media (min-width: 640px) {
  :root {
    --vp-home-hero-image-filter: blur(56px);
  }
}

@media (min-width: 960px) {
  :root {
    --vp-home-hero-image-filter: blur(72px);
  }
}
</style>