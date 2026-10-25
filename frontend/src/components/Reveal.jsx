// Scroll reveal (DESIGN §9): small opacity + 16 px rise, once per element, via one shared
// IntersectionObserver. Content is visible without JS and under reduced motion (see motion.css).
import { useEffect, useRef } from 'react';

let observer;
function observe(el) {
  if (!el) return;
  if (!('IntersectionObserver' in window)) {
    el.classList.add('in');
    return;
  }
  observer ||= new IntersectionObserver(
    (entries) =>
      entries.forEach((e) => {
        if (e.isIntersecting) {
          e.target.classList.add('in');
          observer.unobserve(e.target);
        }
      }),
    { rootMargin: '0px 0px -40px' },
  );
  observer.observe(el);
}

export function Reveal({ as: Tag = 'div', className = '', delay = 0, children, ...rest }) {
  const ref = useRef(null);
  useEffect(() => observe(ref.current), []);
  return (
    <Tag ref={ref} className={`reveal ${className}`} style={delay ? { '--delay': `${delay}ms` } : undefined} {...rest}>
      {children}
    </Tag>
  );
}
