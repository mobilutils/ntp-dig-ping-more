import nextra from 'nextra'

const withNextra = nextra({
    theme: 'nextra-theme-docs',
    themeConfig: './theme.config.jsx',
})

export default withNextra({
    output: 'export',
    images: { unoptimized: true },

     // 🎯 Important : basePath correspond au nom de votre repo GitHub
     // Si votre repo est https://github.com/mobilutils/ntp-dig-ping-more → basePath: '/mon-projet'
     // Si vous utilisez un domaine personnalisé → laissez ''
    basePath: process.env.BASE_PATH || '',
})
