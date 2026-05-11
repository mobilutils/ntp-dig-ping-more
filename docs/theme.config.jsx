// docs/theme.config.jsx
export default {
  logo: <span>🕰️ 🪏 🏓 ⊕... | NTP DIG PING MORE...</span>,
  project: { link: 'https://github.com/mobilutils/ntp-dig-ping-more' },
  logoLink: true,
  useNextSeoProps() {
    return {
      titleTemplate: '%s | NTP DIG PING MORE',
    }
  },
}
