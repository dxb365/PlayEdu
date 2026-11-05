export const MAX_VIDEO_UPLOAD_FILES = 100;
export const MAX_VIDEO_UPLOAD_BYTES = 10 * 1024 ** 3;
export const VIDEO_UPLOAD_CONCURRENCY = 10;

export function videoUploadError(file: Pick<File, "name" | "size" | "type">, batchSize: number) {
  if (batchSize > MAX_VIDEO_UPLOAD_FILES) return "单次最多上传100个视频";
  if (file.size > MAX_VIDEO_UPLOAD_BYTES) return `${file.name} 大小超过10GB`;
  if (file.type !== "video/mp4") return `${file.name} 并不是 mp4 视频文件`;
  return null;
}
