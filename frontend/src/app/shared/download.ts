/**
 * Hands a blob to the browser as a download.
 *
 * The download-and-file-input pair rather than the File System Access API, which is still not
 * available everywhere and would need this as its fallback anyway.
 */
export function download(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  anchor.click();
  // Revoking immediately can cancel the download in some browsers; one turn later is enough.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
