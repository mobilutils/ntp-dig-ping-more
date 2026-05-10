/**
 * Portable <img> that prepends BASE_PATH to image sources.
 *
 * When using `output: 'export'` (static export) with Next.js, the default
 * `<Image>` component is incompatible — it requires the Image Optimization
 * API which only runs in server mode (`next start`). Setting
 * `unoptimized: true` allows `<Image>` to render as plain `<img>` tags,
 * but Next.js does NOT prepend `basePath` to their `src` attributes at
 * build time. The browser resolves `/images/foo.png` to the origin root
 * (e.g. `https://mobilutils.github.io/images/...`) instead of the correct
 * subpath (`https://mobilutils.github.io/ntp-dig-ping-more/images/...`).
 *
 * Nextra's markdown image syntax `![alt](/images/foo.png)` works because
 * Nextra processes it at build time and rewrites paths with basePath.
 * Plain HTML `<img>` tags have no such processing.
 *
 * This component bridges the gap: it reads `process.env.BASE_PATH` at
 * build time and concatenates it with the image `src`, ensuring correct
 * URLs in static-exported sites served under a subpath like GitHub Pages.
 */

const BASE_PATH = process.env.BASE_PATH || '';

export default function Img({ src, alt, style, ...props }) {
  return <img src={`${BASE_PATH}${src}`} alt={alt} style={style} {...props} />;
}
