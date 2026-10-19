// Run in a rendered browser page after its loading state has settled.
// The check reads layout only; it does not change the page or application data.
export function checkWorkspaceLayout() {
  const root = document.documentElement;
  const result = {
    viewportWidth: window.innerWidth,
    clientWidth: root.clientWidth,
    scrollWidth: root.scrollWidth,
  };
  if (result.scrollWidth > result.clientWidth + 1) {
    const overflowing = [...document.querySelectorAll("main *")]
      .filter((element) => element.scrollWidth > element.clientWidth + 1)
      .map(
        (element) => `${element.tagName.toLowerCase()}.${element.className}`,
      );
    throw new Error(
      `Horizontal overflow: ${JSON.stringify(result)}; ${overflowing.join(", ")}`,
    );
  }
  return result;
}
