import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import SettingsView from '../views/SettingsView.vue'
import ExternalFavoritesView from '../views/ExternalFavoritesView.vue'
import XImportView from '../views/XImportView.vue'
import UsersView from '../views/UsersView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView
    },
    {
      path: '/type/:mediaType',
      name: 'type-filter',
      component: HomeView,
      props: true
    },
    {
      path: '/settings',
      name: 'settings',
      component: SettingsView
    },
    {
      path: '/external',
      name: 'external-favorites',
      component: ExternalFavoritesView
    },
    {
      path: '/x-import',
      name: 'x-import',
      component: XImportView
    },
    {
      path: '/users',
      name: 'users',
      component: UsersView
    }
  ]
})

export default router
