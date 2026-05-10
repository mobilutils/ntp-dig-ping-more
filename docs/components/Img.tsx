const BASE_PATH = process.env.BASE_PATH || '';

export default function Img({ src, alt, style, ...props }) {
  return <img src={`${BASE_PATH}${src}`} alt={alt} style={style} {...props} />;
}
