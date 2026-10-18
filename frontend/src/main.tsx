import { PageErrorBoundary } from './components/page-error-boundary'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { DeviceProvider } from './lib/device-context'
import { ThemeProvider } from './lib/theme'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <PageErrorBoundary label="应用">
    <ThemeProvider>
      <DeviceProvider>
        <App />
      </DeviceProvider>
    </ThemeProvider>
    </PageErrorBoundary>
  </StrictMode>,
)
