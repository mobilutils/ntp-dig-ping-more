// docs/pages/_app.jsx
import LocaleSwitcher from '../components/LocaleSwitcher'
import '../styles/switcher.css'

export default function NextraApp({ Component, pageProps }) {
  return (
    <>
      <Component {...pageProps} />
      <div className="locale-switcher-container">
        <LocaleSwitcher />
      </div>
    </>
  )
}
