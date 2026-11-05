import moment from "moment";

export function getToken(): string {
  return window.localStorage.getItem("playedu-backend-token") || "";
}

export function setToken(token: string) {
  window.localStorage.setItem("playedu-backend-token", token);
}

export function clearToken() {
  window.localStorage.removeItem("playedu-backend-token");
}

export function dateFormat(dateStr: string) {
  if (!dateStr) {
    return "-";
  }
  return moment(dateStr).format("YYYY-MM-DD HH:mm");
}

export function timeFormat(dateStr: number) {
  if (!dateStr) {
    return "-";
  }
  var d = moment.duration(dateStr, "seconds");
  let value = d.hours() + "时" + d.minutes() + "分" + d.seconds() + "秒";

  if (d.hours() === 0) {
    value = d.minutes() + "分" + d.seconds() + "秒";
  } else {
    value = d.hours() + "时" + d.minutes() + "分" + d.seconds() + "秒";
  }

  return value;
}

export function generateUUID(): string {
  let guid = "";
  for (let i = 1; i <= 32; i++) {
    let n = Math.floor(Math.random() * 16.0).toString(16);
    guid += n;
    if (i === 8 || i === 12 || i === 16 || i === 20) guid += "-";
  }
  return guid;
}

export function transformBase64ToBlob(
  base64: string,
  mime: string,
  filename: string
): File {
  const arr = base64.split(",");
  const bstr = atob(arr[1]);
  let n = bstr.length;
  const u8arr = new Uint8Array(n);
  while (n--) {
    u8arr[n] = bstr.charCodeAt(n);
  }
  return new File([u8arr], filename, { type: mime });
}

export function parseVideo(file: File): Promise<VideoParseInfo> {
  return new Promise((resolve, reject) => {
    const video = document.createElement("video");
    const url = URL.createObjectURL(file);
    let settled = false;
    const cleanup = () => {
      settled = true;
      clearTimeout(timer);
      video.pause();
      video.removeAttribute("src");
      video.load();
      video.remove();
      URL.revokeObjectURL(url);
    };
    const fail = () => {
      if (settled) return;
      cleanup();
      reject("无法解析视频，请检查文件后重试");
    };
    const timer = setTimeout(fail, 120000);
    video.muted = true;
    video.preload = "auto";
    video.setAttribute("src", url);
    video.setAttribute("width", "400");
    video.setAttribute("height", "300");
    video.addEventListener("error", fail, { once: true });
    video.addEventListener("loadeddata", function () {
      if (settled) return;
      try {
        const canvas = document.createElement("canvas"),
          width = video.width,
          height = video.height;
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext("2d");
        if (!ctx) { fail(); return; }
        ctx.drawImage(video, 0, 0, width, height);
        const info: VideoParseInfo = {
          poster: canvas.toDataURL("image/png"),
          duration: Math.floor(video.duration),
        };
        if (!Number.isFinite(info.duration)) { fail(); return; }
        cleanup();
        resolve(info);
      } catch {
        fail();
      }
    }, { once: true });
  });
}

export function getHost() {
  return window.location.protocol + "//" + window.location.host + "/";
}

export function inStrArray(array: string[], value: string): boolean {
  for (let i = 0; i < array.length; i++) {
    if (array[i] === value) {
      return true;
    }
  }
  return false;
}

export function ValidataCredentials(value: any) {
  let regIdCard =
    /^(^[1-9]\d{7}((0\d)|(1[0-2]))(([0|1|2]\d)|3[0-1])\d{3}$)|(^[1-9]\d{5}[1-9]\d{3}((0\d)|(1[0-2]))(([0|1|2]\d)|3[0-1])((\d{4})|\d{3}[Xx])$)$/;
  if (regIdCard.test(value)) {
    if (value.length === 18) {
      return true;
    }
  }
}

export function checkUrl(value: any) {
  let url = value;
  let str = url.substr(url.length - 1, 1);
  if (str !== "/") {
    url = url + "/";
  }
  return url;
}

export function dateWholeFormat(dateStr: string) {
  if (!dateStr) {
    return "";
  }
  return moment(dateStr).format("YYYY-MM-DD HH:mm:ss");
}

export function transUtcTime(value: string) {
  const specifiedTime = value;
  // 创建一个新的Date对象，传入指定时间
  const specifiedDate = new Date(specifiedTime);
  //将指定时间转换为UTC+0时间
  const utcTime = specifiedDate.toISOString();

  return utcTime;
}
