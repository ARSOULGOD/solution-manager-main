/**
 * An unknown route renders this in place — it never redirects silently
 * (design §14). Real copy/chrome lands with AppShell (task 036); this is
 * the minimum an unmatched path and an invalid `:category` can both use.
 */
export function NotFound() {
  return (
    <main id="main-content" role="alert">
      <h1>Page not found</h1>
      <p>The page you were looking for doesn&apos;t exist.</p>
    </main>
  );
}
