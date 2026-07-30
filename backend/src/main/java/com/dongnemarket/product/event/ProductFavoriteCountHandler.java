package com.dongnemarket.product.event;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.dongnemarket.global.common.event.FavoriteAddedEvent;
import com.dongnemarket.global.common.event.FavoriteRemovedEvent;
import com.dongnemarket.product.repository.ProductRepository;

@Component
public class ProductFavoriteCountHandler {

	private final ProductRepository productRepository;

	public ProductFavoriteCountHandler(ProductRepository productRepository) {
		this.productRepository = productRepository;
	}

	@EventListener
	public void on(FavoriteAddedEvent event) {
		productRepository.incrementFavoriteCount(event.getProductId());
	}

	@EventListener
	public void on(FavoriteRemovedEvent event) {
		productRepository.decrementFavoriteCount(event.getProductId());
	}
}
