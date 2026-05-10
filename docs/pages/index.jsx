import { useRouter } from 'next/router'
import { useEffect } from 'react'

export async function getStaticProps() {
  return {
    props: {
      basePath: process.env.BASE_PATH || '',
     },
    }
}

export default function Index({ basePath }) {
  const router = useRouter()
  useEffect(() => {
    router.replace(`${basePath}/fr/`)
      }, [router])

  return null
}
