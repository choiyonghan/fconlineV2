import type { ApiError } from './types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export class ApiRequestError extends Error {
	constructor(
		public status: number,
		public apiError: ApiError | null
	) {
		super(apiError?.message ?? `API 요청이 실패했습니다 (HTTP ${status})`);
	}
}

function buildUrl(path: string, params?: Record<string, string | number | boolean | undefined>) {
	const url = new URL(path, BASE_URL);
	if (params) {
		for (const [key, value] of Object.entries(params)) {
			if (value !== undefined && value !== null) {
				url.searchParams.set(key, String(value));
			}
		}
	}
	return url;
}

export async function apiGet<T>(
	path: string,
	params?: Record<string, string | number | boolean | undefined>
): Promise<T> {
	const res = await fetch(buildUrl(path, params));
	if (!res.ok) {
		const body = await res.json().catch(() => null);
		throw new ApiRequestError(res.status, body);
	}
	return res.json() as Promise<T>;
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
	const res = await fetch(buildUrl(path), {
		method: 'POST',
		headers: body ? { 'Content-Type': 'application/json' } : undefined,
		body: body ? JSON.stringify(body) : undefined
	});
	if (!res.ok) {
		const errorBody = await res.json().catch(() => null);
		throw new ApiRequestError(res.status, errorBody);
	}
	return res.json() as Promise<T>;
}
