/**
 * The interface scale (Rodada 8, parte 6) is a CSS `zoom` on the body. Event and getBoundingClientRect coordinates are
 * visual (already zoomed), while `left` / `top` / `width` of a fixed element inside the body are multiplied by the zoom:
 * a position measured on screen is divided by it before it is used as a style.
 */
export const pageZoom = () => {
  const z = parseFloat(getComputedStyle(document.body).zoom);
  return Number.isFinite(z) && z > 0 ? z : 1;
};

/** A screen measure (px) as a style measure inside the zoomed body. */
export const unzoom = (v) => v / pageZoom();
