import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	preprocess: vitePreprocess(),
	kit: {
		// GitHub Pages 프로젝트 사이트(username.github.io/repo명/)는 루트가 아니라
		// /repo명 서브패스에서 서빙된다 — deploy-web.yml이 빌드 시 BASE_PATH를 넣어준다.
		// 로컬 dev(npm run dev)는 BASE_PATH가 없어서 그대로 루트로 뜬다.
		paths: {
			base: process.env.BASE_PATH ?? ''
		},
		// CSR 전용 SPA — GitHub Pages 등 정적 호스팅에 그대로 올릴 수 있다.
		adapter: adapter({
			pages: 'build',
			assets: 'build',
			fallback: 'index.html',
			precompress: false,
			strict: true
		})
	}
};

export default config;
