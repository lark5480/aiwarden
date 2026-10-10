import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/chat' },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: 'C 端问答' },
  },
  {
    path: '/admin/consistency',
    name: 'admin-consistency',
    component: () => import('@/views/admin/ConsistencyView.vue'),
    meta: { title: '一致性报告' },
  },
  {
    path: '/admin/usage',
    name: 'admin-usage',
    component: () => import('@/views/admin/UsageView.vue'),
    meta: { title: '用量看板' },
  },
  {
    path: '/admin/eval',
    name: 'admin-eval',
    component: () => import('@/views/admin/EvalView.vue'),
    meta: { title: '评测报告' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title === '' ? 'AIWarden' : `${title} · AIWarden`
})

export default router
