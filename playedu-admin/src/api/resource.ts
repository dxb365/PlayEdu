import client from "./internal/httpClient";

export function nasLibraryStatus() {
  return client.get("/backend/v1/nas-video/status", {});
}

export function nasLibraryBrowse(path: string, page: number, name: string,
  sortField = "name", sortOrder = "asc") {
  return client.get("/backend/v1/nas-video/browse", {
    path, page, name, size: 200, sort_field: sortField, sort_order: sortOrder,
  });
}

export function bulkVideos(operation: "move" | "copy" | "categories", ids: number[], categoryIds: number[]) {
  return client.request({
    method: "POST",
    url: "/backend/v1/resource/bulk",
    timeout: 120000,
    data: { operation, ids, category_ids: categoryIds },
  });
}

export function importNasVideo(path: string, categoryIds: number[]) {
  return client.request({
    method: "POST",
    url: "/backend/v1/nas-video/import",
    timeout: 60000,
    data: { path, category_ids: categoryIds },
  });
}

export function resourceList(
  page: number,
  size: number,
  sortField: string,
  sortAlgo: string,
  name: string,
  type: string,
  categoryIds: string
) {
  return client.get("/backend/v1/resource/index", {
    page,
    size,
    sort_field: sortField,
    sort_algo: sortAlgo,
    name,
    type,
    category_ids: categoryIds,
  });
}

export function createResource(type: string) {
  return client.get("/backend/v1/resource/create", { type });
}

export function storeResource(
  categoryId: number,
  name: string,
  extension: string,
  size: number,
  disk: string,
  fileId: string,
  path: string,
  url: string,
  extra: object
) {
  let data = Object.assign(
    {},
    {
      category_id: categoryId,
      name,
      extension,
      size,
      disk,
      file_id: fileId,
      path,
      url,
    },
    extra
  );
  return client.post("/backend/v1/resource/create", data);
}

export function destroyResource(id: number) {
  return client.destroy(`/backend/v1/resource/${id}`);
}

export function destroyResourceMulti(ids: number[]) {
  return client.post(`/backend/v1/resource/destroy-multi`, {
    ids: ids,
  });
}

export function videoDetail(id: number) {
  return client.get(`/backend/v1/resource/${id}`, {});
}

export function videoUpdate(id: number, params: any) {
  return client.put(`/backend/v1/resource/${id}`, params);
}
