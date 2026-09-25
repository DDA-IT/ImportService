/**
 * Functies voor `/tasks` — `be.dda.catalogimport.web.CatalogImportTaskController` (bouwstap B-B1):
 * alleen-lezen takenlijst, altijd bereikbaar (niet achter `catalogimport.setup-api.enabled`).
 */
import { request, toQueryString } from './http.ts';
import type { PageResult, TaskRow } from './types.ts';

/** GET /tasks — CatalogImportTaskController.tasks */
export function listTasks(
  params: { importLinkId?: number; active?: boolean; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResult<TaskRow>> {
  const query = toQueryString({
    importLinkId: params.importLinkId,
    active: params.active,
    page: params.page,
    size: params.size,
  });
  return request<PageResult<TaskRow>>(`/tasks${query}`, { signal });
}
