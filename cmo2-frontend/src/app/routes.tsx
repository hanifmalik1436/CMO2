import { createBrowserRouter, Navigate } from 'react-router-dom'
import AppShell from './AppShell'
import RequireAuth from './guards/RequireAuth'
import LoginPage from '../features/auth/LoginPage'
import RegisterPage from '../features/auth/RegisterPage'
import VerifyEmailPage from '../features/auth/VerifyEmailPage'
import DashboardPage from '../features/dashboard/DashboardPage'
import CompaniesPage from '../features/companies/CompaniesPage'
import CustomersPage from '../features/customers/CustomersPage'
import CustomerDetailPage from '../features/customers/CustomerDetailPage'
import GhlCallbackPage from '../features/customers/GhlCallbackPage'
import StrangeLoopPage from '../features/strangeLoop/StrangeLoopPage'
import IntegrationsPage from '../features/integrations/IntegrationsPage'
import AssetGeneratorPage from '../features/assets/AssetGeneratorPage'
import ApprovalsPage from '../features/assets/ApprovalsPage'
import StrategistsPage from '../features/strategists/StrategistsPage'
import AnalyticsPage from '../features/analytics/AnalyticsPage'
import ReportsPage from '../features/reports/ReportsPage'
import AlertsPage from '../features/alerts/AlertsPage'
import BillingPage from '../features/billing/BillingPage'
import SettingsPage from '../features/settings/SettingsPage'
import AcceptInvitePage from '../features/auth/AcceptInvitePage'

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/verify-email', element: <VerifyEmailPage /> },
  { path: '/accept-invite', element: <AcceptInvitePage /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [
          { path: '/', element: <Navigate to="/dashboard" replace /> },
          { path: '/dashboard', element: <DashboardPage /> },
          { path: '/customers', element: <CustomersPage /> },
          { path: '/customers/:companyId', element: <CustomerDetailPage /> },
          { path: '/settings/ghl/callback', element: <GhlCallbackPage /> },
          { path: '/companies', element: <CompaniesPage /> },
          { path: '/strange-loop', element: <StrangeLoopPage /> },
          { path: '/integrations', element: <IntegrationsPage /> },
          { path: '/assets', element: <AssetGeneratorPage /> },
          { path: '/approvals', element: <ApprovalsPage /> },
          { path: '/strategists', element: <StrategistsPage /> },
          { path: '/analytics', element: <AnalyticsPage /> },
          { path: '/reports', element: <ReportsPage /> },
          { path: '/alerts', element: <AlertsPage /> },
          { path: '/settings', element: <SettingsPage /> },
          { path: '/billing', element: <BillingPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/dashboard" replace /> },
])
