import { defineConfig } from 'vitepress'

export default defineConfig({
  head: [['link', { rel: 'icon', href: '/assets/favicon.ico' }]],
  title: "DivineMC",
  cleanUrls: true,
  description: "DivineMC is the fork of Purpur compatible with Spigot plugins, offering the best performance for your server.",
  themeConfig: {
    logo: '/assets/logo.png',
    nav: [
      { text: 'Home', link: '/' },
      { text: 'Documentation', link: '/docs/' }
    ],

    sidebar: [
      {
        text: 'Documentation',
        items: [
          { text: 'Overview', link: '/docs/' }
        ]
      },
      {
        text: 'Administration',
        items: [
          { text: 'Getting Started', link: '/docs/admin/getting-started' },
          { text: 'Configuration', link: '/docs/admin/configuration' }
        ]
      },
      {
        text: 'Development',
        items: [
          { text: 'Contributing to DivineMC', link: '/docs/dev/contributing' }
        ]
      }
    ],

    socialLinks: [
      { icon: 'github', link: 'https://github.com/DivineMC/DivineMC' },
      { icon: 'discord', link: 'https://discord.gg/p7cxhw7E2M' }
    ]
  }
})
