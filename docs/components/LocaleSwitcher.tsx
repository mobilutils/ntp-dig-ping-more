'use client'

import { useState, useEffect } from 'react'

// docs/components/LocaleSwitcher.tsx
export default function LocaleSwitcher() {
  const [mounted, setMounted] = useState(false)
  const [currentPath, setCurrentPath] = useState('')

  useEffect(() => {
    setMounted(true)
    setCurrentPath(window.location.pathname)
  }, [])

  // Render nothing during SSR to avoid hydration mismatch
  if (!mounted) {
    return null
  }

   // Extract the page path, stripping locale prefix
  const pagePath = currentPath.replace(/^(\/(en|fr))?(\/.*)?$/, '')
  const currentLocale = currentPath.startsWith('/fr/') ? 'fr' : 'en'

  return (
    <div style={{ display: 'flex', gap: '12px', alignItems: 'center', justifyContent: 'center', marginTop: '24px' }}>
       <a
        href={`/en${pagePath ? '/' + pagePath : ''}`}
        style={{
          padding: '4px 12px',
          borderRadius: '4px',
          border: currentLocale === 'en' ? '2px solid #3b82f6' : '1px solid #e5e7eb',
          background: currentLocale === 'en' ? '#eff6ff' : 'transparent',
          textDecoration: 'none',
          color: 'inherit',
          cursor: 'pointer',
          fontSize: '14px',
          fontWeight: currentLocale === 'en' ? '600' : '400',
         }}
       >EN</a>
       <a
        href={`/fr${pagePath ? '/' + pagePath : ''}`}
        style={{
          padding: '4px 12px',
          borderRadius: '4px',
          border: currentLocale === 'fr' ? '2px solid #3b82f6' : '1px solid #e5e7eb',
          background: currentLocale === 'fr' ? '#eff6ff' : 'transparent',
          textDecoration: 'none',
          color: 'inherit',
          cursor: 'pointer',
          fontSize: '14px',
          fontWeight: currentLocale === 'fr' ? '600' : '400',
         }}
       >FR</a>
     </div>
   )
}
