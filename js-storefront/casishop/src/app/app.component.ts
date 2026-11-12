import { Component, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OutletRefModule, StorefrontComponent } from '@spartacus/storefront';

@Component({
  selector: 'app-root',
  imports: [StorefrontComponent, OutletRefModule, RouterLink],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class App {
  protected readonly title = signal('casishop');
}
